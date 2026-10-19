# CEP Service

API REST para consulta de CEP e cadastro manual de endereços, desenvolvida para um desafio técnico de engenharia de software sênior. Consulta primeiro o PostgreSQL, recorre ao ViaCEP somente na ausência de cadastro e registra consultas bem-sucedidas por auditoria assíncrona com Kafka.

O projeto reúne arquitetura hexagonal, integridade transacional, tratamento seguro de erros e observabilidade em um ambiente local reproduzível com Docker Compose.

| Documentação | Conteúdo |
| --- | --- |
| [Arquitetura](docs/arquitetura.md) | Contexto, containers, componentes e sequências em Mermaid |
| [Decisões arquiteturais](docs/decisoes-arquiteturais.md) | SOLID, consistência, concorrência, confiabilidade e trade-offs |
| [Auditoria Kafka](docs/auditoria-kafka.md) | Contrato JSON, configuração, retries, DLT e idempotência |
| [Monitoramento](docs/monitoramento.md) | Dashboard, PromQL, operação e evidências da validação anterior |

## Requisitos atendidos

| Funcionais | Comportamento implementado |
| --- | --- |
| Consultar CEP | `GET /api/ceps/{cep}`, com exatamente oito dígitos na entrada |
| Priorizar cadastro local | Retorna do PostgreSQL sem acionar ViaCEP quando o endereço existe |
| Consultar provedor externo | ViaCEP apenas após ausência confirmada; resultado válido é persistido antes do retorno |
| Cadastrar endereço manualmente | `POST /api/enderecos`, com validação e conflito 409 por CEP duplicado |
| Auditar consultas | GET bem-sucedido gera evento com CEP, instante UTC, origem e seis campos do endereço |
| Persistir auditoria | Consumer Kafka grava no PostgreSQL com idempotência por `eventId` |

Requisitos não funcionais tratados: separação entre negócio e infraestrutura, consultas parametrizadas, transações curtas, timeouts externos, respostas de erro sem detalhes técnicos, testes sem dependência do ViaCEP público, execução como usuário não root no container e monitoramento HTTP/JVM.

O ambiente é de desenvolvimento e avaliação: não há autenticação da API, alta disponibilidade, garantia de auditoria sem perdas ou SLO de desempenho estabelecido.

## Stack

| Componente | Versão / uso |
| --- | --- |
| Java / Spring Boot | 21 / 4.1.1 |
| Maven | Wrapper do projeto, distribuição 3.10.0 |
| HTTP | Spring MVC e `RestClient` |
| Persistência | PostgreSQL 17, Spring Data JPA para endereços e `JdbcTemplate` para auditoria |
| Migrações | Flyway, com versões gerenciadas pelo Spring Boot |
| Mensageria | Apache Kafka 4.1.1, broker único em KRaft |
| Monitoramento | Actuator, Micrometer, Prometheus 3.14.0 e Grafana 13.2.2 |
| Ambiente | Docker Compose; imagens compatíveis com ARM64 e AMD64 |

## Executar com Docker Compose

Pré-requisitos: Git, Docker Engine/Desktop em execução e Docker Compose v2 com suporte a `--wait`. As portas locais padrão são 8080, 5432, 9092, 9090 e 3000. O primeiro build exige internet para imagens e dependências; somente consultas de CEPs ausentes precisam do ViaCEP em tempo de execução. Java/Maven no host são necessários apenas para execução local e testes.

```sh
git clone https://github.com/Ev1l5on/cep-service.git
cd cep-service
# Execute a cópia somente se ainda não houver .env local.
if [ ! -f .env ]; then cp .env.example .env; fi
```

Edite `.env` e preencha `POSTGRES_PASSWORD` e `GRAFANA_ADMIN_PASSWORD` com senhas locais diferentes. `openssl rand -hex 24` pode gerar cada senha; não a inclua em arquivos versionados. Preserve as credenciais de volumes já inicializados. O `.env` é ignorado pelo Git e excluído do contexto de build.

```sh
docker compose config --quiet
docker compose up -d --build
# Aguarda saúde após a construção da imagem.
docker compose up -d --wait --wait-timeout 240
docker compose ps
curl --fail http://localhost:8080/actuator/health
```

Resultado esperado: `app`, `postgres`, `kafka`, `prometheus` e `grafana` com status `healthy`; Actuator com `status: UP`. Flyway aplica/valida V1 e V2, Hibernate valida a entidade de endereço e KafkaAdmin cria os tópicos necessários. Não é preciso criar tabelas ou tópicos manualmente.

O app aguarda PostgreSQL e Kafka saudáveis; Prometheus aguarda o app; Grafana aguarda Prometheus. Essa ordenação vale na inicialização, não é um mecanismo de recuperação automática de dependências durante a execução.

### Variáveis de ambiente

| Variável | Padrão / finalidade |
| --- | --- |
| `POSTGRES_DB`, `POSTGRES_USER` | `cep_service` |
| `POSTGRES_PASSWORD` | Obrigatória, sem valor padrão |
| `POSTGRES_HOST`, `POSTGRES_PORT` | JVM local: `localhost`, `5432`; Compose app: `postgres`, `5432` |
| `KAFKA_BOOTSTRAP_SERVERS` | JVM local: `localhost:9092`; Compose app: `kafka:29092` |
| `KAFKA_PORT` | `9092`, porta publicada no host |
| `APP_PORT` | `8080`, porta HTTP publicada no host |
| `GRAFANA_ADMIN_USER` | `admin` |
| `GRAFANA_ADMIN_PASSWORD` | Obrigatória no Compose, sem valor padrão |
| `VIACEP_BASE_URL` | `https://viacep.com.br/ws` |
| `VIACEP_CONNECT_TIMEOUT_MS`, `VIACEP_READ_TIMEOUT_MS` | `2000` / `5000` ms, positivos |

O Compose injeta explicitamente os hosts e portas internos no app, independentemente das portas publicadas no host. Ao mudar `KAFKA_PORT`, ajuste também `KAFKA_BOOTSTRAP_SERVERS` na JVM local. `.env` serve à interpolação do Compose; não é carregado automaticamente pelo Java ou IntelliJ. Demais opções de tópicos, grupo, fila e retries estão em [auditoria Kafka](docs/auditoria-kafka.md).

Alterar as variáveis PostgreSQL ou a senha inicial do Grafana não redefine credenciais em volumes existentes. Faça eventual rotação pela administração de cada serviço, sem remover volumes.

## Testar a API

Os comandos usam as portas padrão; adapte a URL se alterar `APP_PORT`.

### Consultar um CEP

```sh
curl -i http://localhost:8080/api/ceps/01001000
```

Resposta 200 exemplificativa; o endereço depende do cadastro local ou do provedor:

```json
{
  "cep": "01001-000",
  "logradouro": "Praça da Sé",
  "complemento": "lado ímpar",
  "bairro": "Sé",
  "localidade": "São Paulo",
  "uf": "SP",
  "erro": null
}
```

O modelo `Endereco` e os snapshots de auditoria possuem **seis campos**. O GET mantém um sétimo campo, `erro: null`, por compatibilidade do contrato REST. O banco armazena CEP sem hífen; a resposta usa `00000-000`.

### Cadastrar manualmente e consultar sem ViaCEP

Use um CEP ainda não cadastrado no seu ambiente:

```sh
curl -i -X POST http://localhost:8080/api/enderecos \
  -H 'Content-Type: application/json' \
  -d '{"cep":"12345678","logradouro":"Rua manual","complemento":"Casa 1","bairro":"Centro","localidade":"São Paulo","uf":"SP"}'

curl -i http://localhost:8080/api/ceps/12345678
```

O POST retorna **201**, `Location: /api/ceps/12345678` e os seis campos persistidos, com CEP formatado. Repetir o cadastro retorna **409**, sem sobrescrever dados. O POST não consulta ViaCEP nem publica evento de consulta.

O CEP deve ter oito dígitos, a localidade deve estar preenchida e ter até 255 caracteres, e a UF deve ser uma sigla brasileira válida em maiúsculas. Logradouro, complemento e bairro podem ser nulos. O cadastro valida estrutura, não a existência do CEP no serviço postal.

**Prioridade manual:** o GET prioriza todo registro local, inclusive o manual. Não há coluna de origem na tabela `enderecos`, atualização de cadastro ou substituição automática de um resultado ViaCEP anterior. Se o CEP já estiver salvo, o POST manual também retorna 409. Em uma disputa, vence a primeira gravação confirmada; se o manual for gravado enquanto um GET aguarda ViaCEP, esse GET recupera o registro manual após o conflito.

### Códigos HTTP e erros

| Status | Situação |
| --- | --- |
| 200 / 201 | Consulta / cadastro concluído |
| 400 | CEP ou cadastro inválido; corpo JSON inválido |
| 404 | CEP inexistente confirmado pelo indicador `erro` do ViaCEP |
| 405 | Método HTTP não suportado para a rota |
| 409 | CEP já cadastrado no POST |
| 502 | Resposta externa inválida, vazia ou HTTP externo inesperado |
| 503 | Indisponibilidade do ViaCEP ou PostgreSQL |
| 504 | Timeout de conexão/leitura do ViaCEP |
| 500 | Falha interna ou outra falha de persistência |

```sh
curl -i http://localhost:8080/api/ceps/invalido
```

Exemplo de resposta 400; `timestamp` varia:

```json
{
  "timestamp": "2026-10-19T12:00:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "O CEP deve conter exatamente 8 dígitos",
  "path": "/api/ceps/invalido"
}
```

O `ApiExceptionHandler` centraliza esse formato. Falha PostgreSQL nunca significa ausência de CEP e não autoriza fallback para ViaCEP. Apenas `erro=true` ou `erro="true"` do provedor significa CEP inexistente; HTTP 404 externo inesperado resulta em 502. HTTP externos 408/504 resultam em 504 e 429/503 em 503.

## Arquitetura e organização

As dependências de tecnologia apontam para as portas da aplicação; domínio e casos de uso não importam Spring, JPA, Kafka ou classes HTTP. `CepConfiguration` monta os casos de uso por construtor. Não há interfaces de entrada redundantes para os três casos de uso atuais.

```text
src/main/java/com/desafio/cep_service/
├── domain/
│   ├── model/             # Cep e Endereco
│   └── exception/         # Invariantes de entrada e ausência
├── application/
│   ├── usecase/           # CepService, CadastrarEnderecoService, RegistrarConsultaService
│   ├── port/out/          # ViaCEP, endereços, publicação e logs
│   ├── event/             # ConsultaCepEvento
│   └── exception/         # Falhas sem dependência de HTTP
└── infrastructure/
    ├── adapter/in/        # REST e listener Kafka
    ├── adapter/out/       # ViaCEP, PostgreSQL e producer Kafka
    ├── config/            # Composição de dependências e configuração técnica
    └── serialization/     # Contrato JSON da mensageria
src/main/resources/db/migration/  # V1 enderecos; V2 consulta_logs
src/test/java/                   # Testes unitários, REST e integração
monitoring/                      # Prometheus, provisionamento e dashboard Grafana
docs/                            # Diagramas, decisões, operação e apresentação
```

[Diagramas de contexto, containers, componentes e sequências](docs/arquitetura.md).

V1 cria `enderecos`, com chave técnica, seis campos, timestamps e `UNIQUE(cep)`. V2 cria `consulta_logs`, com UUID único do evento, versão, CEP, instante UTC, origem, snapshot JSONB e data de gravação. Os índices das constraints UNIQUE atendem às buscas por CEP e idempotência; não há índices extras para relatórios. Migrações aplicadas não devem ser editadas. `ddl-auto=validate`, `open-in-view=false` e `spring.flyway.clean-disabled=true` são mantidos.

Cada operação de persistência usa uma transação local curta. Não há transação aberta durante a chamada ao ViaCEP. A constraint arbitra concorrência; a recuperação do GET acontece depois do rollback, em nova transação.

## Auditoria assíncrona

Após obter um endereço confirmado, `CepService` cria `ConsultaCepEvento` com `eventId` UUID novo, `eventVersion=1`, CEP, `consultedAt` UTC, origem `DATABASE`/`VIACEP` e snapshot dos seis campos. Origem representa a fonte efetivamente retornada, não a procedência histórica do cadastro.

`PublicarConsultaPort` leva ao `KafkaConsultaPublisher`, que enfileira em executor de dois workers e fila limitada a 1000 tarefas. O worker envia JSON ao tópico `cep-consultas-v1`, usando o CEP como chave. O HTTP não aguarda confirmação Kafka nem persistência do log. Falhas imediatas, rejeição da fila e falhas assíncronas geram diagnóstico seguro e preservam o retorno da consulta.

O `ConsultaAuditoriaListener` delega a `RegistrarConsultaService`; a gravação usa SQL parametrizado e `ON CONFLICT (event_id) DO NOTHING`. O commit PostgreSQL ocorre antes do retorno do listener e do commit do offset. Reentregas do mesmo evento mantêm uma linha; novos GETs geram novos eventos e novos logs.

O consumidor tenta processar até três vezes por padrão, com 1s entre tentativas. Evento estruturalmente inválido vai direto à DLT. Falha persistente após retries segue para `cep-consultas-v1.DLT`, com confirmação de envio; se a DLT falhar, o offset não é confirmado. Não há reprocessamento automático da DLT.

**Limite de entrega:** a fila do producer está em memória e pode perder eventos. Após aceite pelo Kafka, há processamento com possibilidade de reentrega e persistência idempotente, sujeito à retenção e durabilidade do ambiente. Não há transação distribuída, transactional outbox ou garantia exactly-once de ponta a ponta.

### Comprovar a gravação e acompanhar consumo

A auditoria é eventual: aguarde o consumidor e consulte o PostgreSQL, não apenas logs:

```sh
docker compose exec -T postgres sh -c 'exec psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"' <<'SQL'
SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;
SELECT cep, logradouro, localidade, uf FROM enderecos ORDER BY id DESC LIMIT 5;
SELECT event_id, cep, consulted_at, source, response_data
FROM consulta_logs ORDER BY id DESC LIMIT 5;
SELECT event_id, count(*) FROM consulta_logs GROUP BY event_id HAVING count(*) > 1;
SQL

docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server kafka:29092 --describe --group cep-service-auditoria-v1
```

A consulta de duplicados deve retornar zero linhas. Partições Kafka vazias podem mostrar offset/lag `-`; nas utilizadas, lag zero significa que o grupo alcançou o final naquele instante. Adapte grupo/tópicos se sobrescrever os padrões. [Contrato e operação da DLT](docs/auditoria-kafka.md).

## Observabilidade

| Interface | URL local |
| --- | --- |
| Saúde | http://localhost:8080/actuator/health |
| Métricas | http://localhost:8080/actuator/prometheus |
| Prometheus / targets | http://localhost:9090/targets |
| Grafana | http://localhost:3000 |
| Dashboard | http://localhost:3000/d/cep-service-observabilidade |

Autentique no Grafana com as credenciais do `.env`. Datasource e dashboard **CEP Service — Observabilidade** são provisionados por arquivos; não é necessário configurá-los na UI. Prometheus coleta a aplicação pela rede interna a cada 15s.

Os sete painéis exibem volume HTTP, requisições/s, p95, erros 5xx, disponibilidade de scrape, memória JVM e CPU do processo. `up=1` significa scrape bem-sucedido, não saúde funcional de todas as dependências. p95 depende de tráfego e é aproximado pelo histograma; `increase` é extrapolado. Não existem contadores específicos de negócio/auditoria nem exporters PostgreSQL/Kafka. Actuator publica somente `health` e `prometheus`, sem detalhes internos de saúde.

Gere tráfego em um CEP local e aguarde dois ou mais scrapes. Detalhes, consultas PromQL e diagnóstico em [monitoramento](docs/monitoramento.md).

## Testes e execução pelo IntelliJ

A referência validada é de **207 testes aprovados**, zero falhas, erros ou ignorados. A etapa documental usa esse resultado anterior e os relatórios Surefire locais; não representa nova execução da suíte.

Para reproduzir, tenha Java 21 no host, inicie dependências e exporte um `.env` local confiável para o processo Maven:

```sh
docker compose up -d --wait postgres kafka
set -a
. ./.env
set +a
./mvnw clean test
```

Testes unitários usam mocks. Os testes de integração usam os serviços Docker reais, schemas PostgreSQL e tópicos/grupos Kafka isolados; não usam Testcontainers nem o ViaCEP público. Os schemas/tópicos permanecem no ambiente: use infraestrutura dedicada para execuções recorrentes. A suíte cobre contratos, validação, parâmetros SQL, concorrência, fronteiras transacionais, producer assíncrono, retries, DLT e offsets.

Para executar no host, pare somente o app Docker e use a mesma sessão com variáveis exportadas:

```sh
docker compose stop app
./mvnw spring-boot:run
```

No IntelliJ, configure JDK 21, a classe `com.desafio.cep_service.CepServiceApplication` e `Environment variables` com PostgreSQL em `localhost`, porta publicada, banco, usuário, senha e `KAFKA_BOOTSTRAP_SERVERS=localhost:9092`. A IDE não herda automaticamente o `.env` do Compose. Configure também o processo de testes/Maven e não versione Run Configurations com credenciais. O Prometheus do Compose aponta para o serviço `app`; monitorar a JVM no host exige configuração específica, não incluída.

O Dockerfile usa Wrapper/JDK 21 no build, cache de dependências e JRE 21 no runtime, com UID/GID 10001. O build executa `package -DskipTests`, compilando testes sem executá-los porque a integração depende dos serviços externos ao build. A suíte completa permanece executável separadamente; o POM não desabilita testes.

## Operação, decisões e evoluções

```sh
docker compose logs --tail=100 app
# Parada sem excluir containers ou volumes.
docker compose stop
# Retomada e espera dos healthchecks.
docker compose up -d --wait --wait-timeout 240
```

Preserve o nome do projeto Compose e os volumes `postgres_data`, `kafka_data`, `prometheus_data` e `grafana_data`. Não use `docker compose down -v`. O app tem limite de 768 MiB/1 CPU; Prometheus e Grafana, 512 MiB/0,5 CPU cada. App e monitoramento usam `restart: unless-stopped`; PostgreSQL e Kafka mantêm a política padrão do Compose. A parada do app permite até 60s. Volumes persistentes não substituem backups.

Decisões principais: transações curtas sem HTTP externo, banco como autoridade de unicidade, SQL parametrizado, auditoria desacoplada com perda possível antes do broker e consumer idempotente. A análise completa está em [decisões arquiteturais](docs/decisoes-arquiteturais.md).

Evoluções **não implementadas**: outbox durável se auditoria sem perdas for requisito, autenticação/autorização, TLS/SASL, política explícita de atualização manual, alertas/SLOs, métricas de auditoria, backups e replicação. Não há Kubernetes, CI/CD ou implantação em nuvem. O ambiente local usa broker único e portas restritas a `127.0.0.1`.

A [auditoria da etapa 04](docs/auditoria-etapa-04.md) é um registro histórico com 153 testes naquele momento. A [validação do monitoramento](docs/monitoramento.md) registra a suíte posterior de 207 testes e a confirmação de Kafka → PostgreSQL.
