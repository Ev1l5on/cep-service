# Monitoramento do CEP Service — etapa 09

## Arquitetura e escopo

A infraestrutura Actuator/Micrometer expoe metricas HTTP e JVM em
`/actuator/prometheus`. Prometheus coleta a cada 15 segundos pela rede interna
Compose (`app:8080`), armazena series em seu volume e atende consultas do Grafana
(`http://prometheus:9090`). Grafana provisiona o datasource e o dashboard a partir
de arquivos versionados. Nao houve alteracao de dominio, casos de uso, contratos
REST, migracoes Flyway ou processamento Kafka.

Actuator e `micrometer-registry-prometheus` ja existiam no POM, com versoes
gerenciadas pelo Spring Boot 4.1.1; nenhuma dependencia nova foi necessaria.
A exposicao web permite somente `health` e `prometheus`. Detalhes/componentes
de health permanecem ocultos; endpoints `env`, `configprops`, `heapdump`,
`loggers` e similares nao sao publicados.

O histograma `http.server.requests` foi habilitado para calcular p95 a partir
de buckets. A tag comum `application=cep-service` identifica o processo.
As rotas HTTP usam templates, como `/api/ceps/{cep}`, sem CEP, query strings,
headers ou corpos nas labels. Os paineis de trafego excluem `/actuator.*` para
nao contar healthchecks/scrapes como requisicoes da API.

## Servicos e arquivos

| Servico | Endereco local | Comunicacao interna |
| --- | --- | --- |
| app | http://localhost:8080 | `app:8080` |
| postgres | localhost:5432 | `postgres:5432` |
| kafka | localhost:9092 | `kafka:29092` |
| prometheus | http://localhost:9090 | `prometheus:9090` |
| grafana | http://localhost:3000 | `grafana:3000` |

Todas as portas publicadas permanecem vinculadas a `127.0.0.1`. As portas HTTP,
PostgreSQL e Kafka podem usar os overrides locais ja existentes. Prometheus e
Grafana usam, respectivamente, `prom/prometheus:v3.14.0` e
`grafana/grafana:13.2.2`, sem `latest` ou plataforma forcada. Os manifests das
duas imagens foram verificados para ARM64 e AMD64; a validacao local usa ARM64.

- `monitoring/prometheus.yml`: job `cep-service`, coleta e avaliacao a cada 15s,
  timeout de coleta de 10s.
- `monitoring/grafana/provisioning/datasources/prometheus.yml`: datasource
  Prometheus padrao, UID estavel `cep-prometheus`, URL interna, intervalo 15s.
- `monitoring/grafana/provisioning/dashboards/cep-service.yml`: provider de
  arquivos, pasta **CEP Service**, atualizacao a cada 30s.
- `monitoring/grafana/dashboards/cep-service.json`: dashboard
  **CEP Service — Observabilidade**, UID `cep-service-observabilidade`, sete
  paineis e atualizacao a cada 15s.

Configuracoes sao montadas somente para leitura. Alteracoes devem ser feitas
nos arquivos versionados, pois a edicao do dashboard/datasource na UI e bloqueada.
Nao ha plugins externos, exporters adicionais ou configuracao manual obrigatoria.

## Credenciais e inicializacao

1. Se nao existir, copie `.env.example` para `.env`.
2. Defina `POSTGRES_PASSWORD` com a credencial do volume PostgreSQL existente.
3. Acrescente `GRAFANA_ADMIN_USER` (padrao `admin`) e
   `GRAFANA_ADMIN_PASSWORD` ao `.env`. Gere uma senha exclusiva localmente,
   por exemplo com `openssl rand -hex 24`; nao a coloque no README, comandos
   versionados ou capturas de tela. O `.env` e ignorado pelo Git e pelo build Docker.
4. Inicie o sistema:

```sh
docker compose config --quiet
docker compose up -d --build
docker compose ps
```

Para aguardar todos os healthchecks, use
`docker compose up -d --wait --wait-timeout 240` apos o build. PostgreSQL e Kafka
mantem seus healthchecks. App usa Actuator, Prometheus usa `/-/ready` e Grafana
usa `/api/health`. O healthcheck do Grafana nao prova que o datasource responde;
a API do datasource e o alvo Prometheus tambem devem ser verificados.

Abra <http://localhost:3000>, autentique com o usuario/senha locais e acesse
**Dashboards > CEP Service > CEP Service — Observabilidade**, ou diretamente
<http://localhost:3000/d/cep-service-observabilidade>. Nao e preciso adicionar
um datasource ou importar JSON pela interface. Cadastro de usuarios e acesso
anonimo estao desativados. A senha e definida na inicializacao do volume;
editar `.env` nao altera a senha de um Grafana ja inicializado. Para rotacao,
use a alteracao de senha autenticada na UI/administracao, preservando o volume.

Prometheus e o endpoint de metricas nao exigem autenticacao neste ambiente
local; suas portas sao restritas ao loopback. Nao publique essas interfaces
na internet sem controle de acesso/TLS. Admin API e lifecycle HTTP do Prometheus
nao foram habilitados.

## Gerar trafego sem depender do ViaCEP publico

O GET abaixo normalmente usa o endereco ja persistido. Se o CEP nao existir no
ambiente, a primeira consulta depende do ViaCEP; depois utiliza o PostgreSQL.
Para um ensaio totalmente local, cadastre um CEP pela API e use-o no loop.
Cada GET bem-sucedido tambem gera auditoria assincrona; os dados sao preservados.

```sh
for attempt in $(seq 1 30); do
  curl --fail --silent http://localhost:8080/api/ceps/01001000 > /dev/null
  sleep 1
done
# Validacao 400, sem acesso ao ViaCEP ou banco pelo caso de uso.
curl -i http://localhost:8080/api/ceps/invalido
```

Aguarde pelo menos 30s (dois scrapes); a janela de rate do Grafana e de pelo
menos 1 minuto, portanto mais trafego por 1–2 minutos melhora a demonstracao.
A escala de memoria/CPU inclui requisicoes e consumo Kafka. Nao provoque erros
5xx desligando dependencias apenas para preencher um painel: zero e o resultado
esperado de um ambiente saudavel.

## Consultas PromQL do dashboard

`$__range` e o intervalo selecionado e `$__rate_interval` e calculado pelo
Grafana a partir da resolucao e do scrape de 15s. Para executar no Prometheus,
substitua essas variaveis por duracoes como `1h` e `1m`. O seletor
`job="cep-service"` evita somar outros alvos.

### Volume HTTP no periodo

```promql
sum(increase(http_server_requests_seconds_count{job="cep-service",uri!~"/actuator.*"}[$__range]))
```

### Taxa de requisicoes HTTP por segundo

```promql
sum by (method, uri) (rate(http_server_requests_seconds_count{job="cep-service",uri!~"/actuator.*"}[$__rate_interval]))
```

### Latencia HTTP p95

```promql
histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{job="cep-service",uri!~"/actuator.*"}[$__rate_interval])))
```

### Taxa de erros HTTP 5xx

```promql
(sum(rate(http_server_requests_seconds_count{job="cep-service",uri!~"/actuator.*",status=~"5.."}[$__rate_interval])) or on() vector(0)) / clamp_min(sum(rate(http_server_requests_seconds_count{job="cep-service",uri!~"/actuator.*"}[$__rate_interval])), 1e-9)
```

### Disponibilidade — scrape da aplicacao

```promql
up{job="cep-service"}
```

### Memoria JVM utilizada

```promql
sum by (area) (jvm_memory_used_bytes{job="cep-service"})
```

### Utilizacao de CPU do processo JVM

```promql
process_cpu_usage{job="cep-service"}
```

O volume usa `increase`: e uma estimativa extrapolada para o intervalo, nao um
contador transacional. O p95 e uma aproximacao do histograma e pode ficar sem
valor/NaN se nao houver requisicoes na janela. A taxa 5xx mostra zero quando
nao ha series de erro; isso nao significa que todas as dependencias estao saudaveis.
Disponibilidade e `up` do Prometheus: scrape bem-sucedido, nao um SLO de negocio
nem a prova de persistencia de auditoria. CPU e a medida normalizada fornecida
pelo JVM/Micrometer e pode variar com suporte de cgroups/ambiente. Memoria JVM
heap/nonheap nao inclui toda a memoria nativa ou de outros containers.

## Auditoria Kafka

O escopo prioriza HTTP/JVM e preserva a semantica de entrega existente. Nao foram
adicionados contadores de negocio ao producer, listener, adaptador de persistencia
ou recuperador DLT. Contar tentativas como eventos entregues ou contar uma
reentrega idempotente como nova persistencia produziria diagnosticos incorretos.
Essas metricas exigem definir separadamente tentativa, confirmacao do broker,
commit efetivo, duplicata, retry e confirmacao de DLT, com cobertura especifica.

Os paineis atuais nao comprovam entrega Kafka. Para isso, consulte os registros
reais em `consulta_logs` e os offsets do grupo, conforme a secao de auditoria do
README e [auditoria-kafka.md](auditoria-kafka.md). O envio permanece assincrono
com fila limitada, retry/DLT do consumidor e idempotencia por `event_id`.
As limitacoes de perda do producer direto em memoria continuam aplicaveis.

## Procedimento de validacao

```sh
set -a
. ./.env
set +a
./mvnw clean test
git diff --check
docker compose up -d --build
docker compose ps
curl --fail http://localhost:8080/api/ceps/01001000
curl --fail http://localhost:8080/actuator/health
curl --fail http://localhost:8080/actuator/prometheus
curl --fail http://localhost:9090/-/ready
curl --fail 'http://localhost:9090/api/v1/query?query=up%7Bjob%3D%22cep-service%22%7D'
curl --fail http://localhost:3000/api/health
```

Confira em <http://localhost:9090/targets> que `cep-service` esta UP. No Grafana,
confirme o datasource Prometheus e os sete paineis. Via API autenticada, verifique
`/api/datasources/uid/cep-prometheus`,
`/api/datasources/uid/cep-prometheus/health` e
`/api/dashboards/uid/cep-service-observabilidade`. Evite colocar a senha em
argumentos de linha de comando ou historico; use sessao autenticada na UI ou um
cliente que leia a credencial diretamente do ambiente.

Valide as consultas PromQL pelo `/api/v1/query` e tambem via datasource Grafana.
As metricas esperadas incluem `http_server_requests_seconds_count`,
`http_server_requests_seconds_bucket`, `jvm_memory_used_bytes` e
`process_cpu_usage`. `up` e criada pelo Prometheus ao coletar o alvo.
Confirme que `/actuator/env`, `/actuator/configprops`, `/actuator/heapdump` e
`/actuator/loggers` retornam 404. Observe que o contador HTTP de uma requisicao
so fica disponivel apos a resposta ser concluida.

Para confirmar a auditoria sem depender somente de logs, capture o ultimo ID
antes de um GET, aguarde a persistencia assincrona e consulte no PostgreSQL:

```sh
docker compose exec -T postgres sh -c 'exec psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"' <<'SQL'
SELECT event_id, cep, source, consulted_at, response_data
FROM consulta_logs WHERE cep = '01001000' ORDER BY id DESC LIMIT 5;
SELECT event_id, count(*) FROM consulta_logs GROUP BY event_id HAVING count(*) > 1;
SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;
SQL
```

A validacao automatizada de integracao continua usando schemas/topicos
isolados, sem consultar o ViaCEP publico. Nao foram removidos testes.

## Persistencia e limites operacionais

`prometheus_data` preserva as series e `grafana_data` preserva usuarios e banco
interno. Os volumes anteriores PostgreSQL/Kafka permanecem intactos. Para parar,
use `docker compose stop`; para retomar, `docker compose up -d --wait`.
Nunca utilize `docker compose down -v` ou remova volumes para trocar senhas.

Prometheus retém ate 7 dias ou aproximadamente 512 MB de blocos de series
(o WAL e outros arquivos podem elevar o uso total). Prometheus e Grafana tem
cada um limite de 512 MiB e 0,5 CPU e restart `unless-stopped`. Esses valores
servem ao desafio local e podem precisar de ajuste conforme carga/cardinalidade.
Nao ha alertas, alta disponibilidade, exporters PostgreSQL/Kafka, dashboards de
negocio, TLS ou autenticacao Prometheus nesta etapa. Persistencia do Grafana
nao e uma estrategia de backup. Nao houve CI/CD nem deploy cloud.

## Resultado da validacao local

Validado em 18/10/2026 (horario de Brasilia), Mac Apple Silicon ARM64:

| Verificacao | Resultado |
| --- | --- |
| `./mvnw clean test` com `.env` exportado | BUILD SUCCESS; 207 testes; 0 falhas, 0 erros, 0 ignorados; 18,276s |
| `docker compose config --quiet` | Configuracao valida |
| `docker compose up -d --build` | Build e inicializacao concluidos, volumes existentes preservados |
| `docker compose ps` | app, postgres, kafka, prometheus e grafana healthy |
| `promtool check config /etc/prometheus/prometheus.yml` | SUCCESS |
| GET `/api/ceps/01001000` | 200; contrato de sete campos preservado |
| GET `/actuator/health` | 200 UP; sem componentes/detalhes internos |
| GET `/actuator/prometheus` | 200; contador e buckets HTTP, memoria JVM e CPU presentes |
| Endpoints env/configprops/heapdump/loggers/beans/metrics/shutdown | 404 |
| Prometheus `/-/ready` e API de targets | 200; target `http://app:8080/actuator/prometheus` UP |
| Grafana `/api/health` | 200; banco interno OK |
| API do datasource Grafana | Provisionado, padrao, read-only; health OK |
| API do dashboard Grafana | Provisionado; titulo correto e sete paineis |
| PromQL dos sete paineis | Todas retornaram series com valores finitos |
| `/api/ds/query` Grafana | Sete consultas executadas pelo datasource, com frames retornados |
| Auditoria Kafka apos trafego | 33 GETs / 33 novos registros confirmados por SQL em `consulta_logs` |
| Idempotencia existente | Nenhum `event_id` duplicado na tabela |
| Flyway | V1 e V2 com `success=true`, arquivos preservados |
| `git diff --check` | Sem erros |

A coleta apresentou p95 aproximado de 10,68ms, CPU do processo de 5,53% e
taxa 5xx de 0% durante a amostra; sao valores pontuais, nao limites ou garantias
de desempenho. O volume estimado por `increase` foi 33,63 para 33 GETs gerados,
por extrapolacao na janela selecionada. Labels da API usaram `/api/ceps/{cep}`,
sem expor o CEP concreto ou credenciais.

Exemplo de registro confirmado diretamente no PostgreSQL:

- `event_id`: `78728357-3bc0-4f87-a761-1ce6e6f296db`.
- `cep`: `01001000`; `source`: `DATABASE`.
- `consulted_at`: `2026-10-19T02:00:54.806008Z`.
- `response_data`: seis campos do endereco, mantendo o contrato existente.

As credenciais Grafana foram geradas somente no `.env` local ignorado. Nenhum
segredo foi incluido nos arquivos versionados, metricas verificadas ou nesta
documentacao. Nenhum commit, push ou deploy em nuvem foi executado.

## Referencias oficiais

- [Spring Boot: metricas e histogramas](https://docs.spring.io/spring-boot/reference/actuator/metrics.html).
- [Prometheus: configuracao de scraping](https://prometheus.io/docs/prometheus/latest/configuration/configuration/).
- [Grafana: provisionamento de datasources e dashboards](https://grafana.com/docs/grafana/latest/administration/provisioning/).
