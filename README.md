# CEP Service

A etapa 07 adiciona a aplicacao ao Docker Compose. As etapas 05 e 06 incluem
auditoria assincrona via Kafka e PostgreSQL.
Contrato do evento, retry, DLT, limites de confiabilidade e instrucoes de
operacao: [docs/auditoria-kafka.md](docs/auditoria-kafka.md).

## Iniciar o sistema completo com Docker

Pre-requisitos: Docker Engine/Desktop em execucao, Docker Compose v2 ou superior,
portas locais 8080, 5432, 9092, 9090 e 3000 livres e acesso a internet para baixar imagens,
dependencias Maven e consultar ViaCEP. Nao e necessario instalar Java ou Maven
no host para executar o sistema completo. As imagens Temurin Java 21, PostgreSQL
17 e Kafka 4.1.1 suportam ARM64 (Apple Silicon) e AMD64; nao se forca emulacao.

1. Se ainda nao existir, copie `.env.example` para `.env`.
2. Preencha `POSTGRES_PASSWORD` e `GRAFANA_ADMIN_PASSWORD` somente no `.env`,
   com senhas diferentes geradas localmente
   (por exemplo, `openssl rand -hex 24`). Nunca versione esse arquivo. Em um volume
   existente, preserve as credenciais ja utilizadas; editar `.env` nao troca a senha
   armazenada pelo PostgreSQL.
3. Inicie todos os servicos:

```sh
docker compose up -d --build
```

O primeiro build baixa as dependencias. Para aguardar tambem os healthchecks:

```sh
docker compose up -d --build --wait --wait-timeout 240
docker compose ps
curl --fail http://localhost:8080/actuator/health
```

O esperado e `app`, `postgres`, `kafka`, `prometheus` e `grafana` saudaveis,
e `status: UP` no Actuator.
O app aguarda os healthchecks das dependencias. Flyway aplica/valida V1 e V2 e
o KafkaAdmin cria automaticamente os topicos de consulta e DLT; nao e necessario
criar tabelas ou topicos manualmente. O healthcheck do app verifica o Actuator e
a conexao de banco; nao substitui a verificacao do fluxo Kafka ate o PostgreSQL.

| Configuracao | IntelliJ / JVM no Mac | App no Docker Compose |
| --- | --- | --- |
| PostgreSQL | `localhost:${POSTGRES_PORT:-5432}` | `postgres:5432` |
| Kafka | `localhost:${KAFKA_PORT:-9092}` | `kafka:29092` |
| HTTP | `localhost:8080` | `localhost:${APP_PORT:-8080}` |
| Variaveis | exportar ou configurar na IDE | `environment` do servico `app` |

Os valores da coluna local representam variaveis shell, com seus valores padrao.
O Compose usa `.env` para interpolacao e injeta as variaveis declaradas no app.
Hosts/portas internos permanecem fixos, mesmo ao alterar portas publicadas no Mac.
A API e as portas PostgreSQL/Kafka sao publicadas somente em `127.0.0.1`.
Para mudar a porta HTTP publicada, configure `APP_PORT` no `.env`; dentro do
container, a aplicacao continua usando 8080. Nao execute a JVM local e o app
Docker simultaneamente na mesma porta; use `docker compose stop app` antes da IDE.

### Consultar e cadastrar enderecos

```sh
curl --fail http://localhost:8080/api/ceps/01001000

curl -i -X POST http://localhost:8080/api/enderecos \
  -H 'Content-Type: application/json' \
  -d '{"cep":"12345678","logradouro":"Rua manual","complemento":"Casa 1","bairro":"Centro","localidade":"São Paulo","uf":"SP"}'

curl --fail http://localhost:8080/api/ceps/12345678
```

O primeiro cadastro retorna 201 e repetir o mesmo CEP retorna 409. O GET retorna
200 com os sete campos originais, priorizando o cadastro local. Um CEP ausente
no banco e consultado no ViaCEP e persistido antes da resposta. A publicacao do
evento acontece em executor separado, sem aguardar Kafka ou gravacao da auditoria.
POST nao publica eventos de consulta.

### Verificar auditoria e migracoes

Cada GET bem-sucedido gera um novo `eventId`. A auditoria e eventual: aguarde o
consumer antes de consultar o registro. Os comandos abaixo usam as credenciais
ja presentes no container, sem imprimi-las:

```sh
docker compose exec -T postgres sh -c 'exec psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"' <<'SQL'
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;
SELECT cep, logradouro, localidade, uf FROM enderecos ORDER BY id DESC LIMIT 10;
SELECT event_id, cep, consulted_at, source, response_data
FROM consulta_logs ORDER BY id DESC LIMIT 10;
SELECT event_id, count(*) FROM consulta_logs GROUP BY event_id HAVING count(*) > 1;
SQL

docker compose exec kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server kafka:29092 --list
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server kafka:29092 --describe --group cep-service-auditoria-v1
docker compose logs --tail=100 app
```

Topicos padrao: `cep-consultas-v1` e `cep-consultas-v1.DLT`. Consulta e DLT tem
3 particoes; nomes/grupo podem ser sobrescritos por variaveis de ambiente.
Particoes vazias podem mostrar offset/lag `-`. Reentregar o mesmo `eventId` nao
cria outro log, devido a constraint UNIQUE e ao INSERT idempotente do consumidor.
Um novo GET tem novo `eventId` e, portanto, cria outro log normalmente.
Falhas do producer nao alteram o sucesso HTTP; envio direto em memoria pode
perder eventos em falhas do processo, indisponibilidade prolongada ou fila cheia.
Retry, DLT e limites estao descritos em [auditoria Kafka](docs/auditoria-kafka.md).

### Build, recursos e operacao

O `Dockerfile` usa Maven Wrapper/JDK 21 no build e apenas o JAR executavel com
JRE 21 no runtime. Dependencias Maven usam cache BuildKit. O contexto e limitado
por `.dockerignore`, excluindo `.env`, credenciais, Git, IDE e artefatos locais.
O processo Java executa como UID/GID 10001, sem root; curl verifica o Actuator.
Nao ha fontes nem credenciais embutidas na imagem final.

O build da imagem executa `package -DskipTests`, compilando os testes sem
executa-los, porque os testes de integracao dependem de PostgreSQL/Kafka reais
fora do ambiente isolado de build. Isso nao muda o POM nem desativa a suite:
execute obrigatoriamente a validacao completa separada, conforme abaixo.

O app tem limite local de 768 MiB, 1 CPU e heap de ate 70% da memoria disponivel.
Esses limites podem ser ajustados no Compose se o ambiente exigir. Os logs vao
para stdout/stderr. `restart: unless-stopped` reinicia o app se o processo falhar
ou o Docker reiniciar; um `stop` explicito e respeitado. A parada permite ate 60s
para encerrar HTTP, executor e consumer. `depends_on` ordena a inicializacao,
mas nao recupera automaticamente dependencias que falhem depois: PostgreSQL e
Kafka preservam sua configuracao existente e podem exigir reinicio manual.

```sh
# Parar todos os servicos, preservando containers e volumes.
docker compose stop
# Retomar e aguardar a saude das dependencias e do app.
docker compose up -d --wait
# Reiniciar apenas a aplicacao.
docker compose restart app
```

Os volumes `postgres_data` e `kafka_data` permanecem intactos. Preserve o nome
do projeto Compose para reutiliza-los. Nunca utilize `docker compose down -v`.
As imagens e o broker unico sao adequados ao desafio local; nao representam
alta disponibilidade ou configuracao de seguranca para producao.

### Executar a suite completa

Requer Java 21 no host. Inicie as dependencias e disponibilize `.env` ao processo
Maven (somente atribuicoes shell confiaveis, sem imprimir credenciais):

```sh
docker compose up -d --wait postgres kafka
set -a
. ./.env
set +a
./mvnw clean test
git diff --check
```

Os 207 testes existentes incluem PostgreSQL/Kafka reais em schemas/topicos
isolados e nao dependem do ViaCEP publico. Os schemas/topicos de teste permanecem
no ambiente; use infraestrutura dedicada em execucoes recorrentes.

## Monitoramento — etapa 09

O Compose tambem inicia Prometheus e Grafana, com datasource e dashboard
provisionados automaticamente, sem configuracao manual pela interface.

- API / saude: <http://localhost:8080/actuator/health>.
- Metricas Micrometer: <http://localhost:8080/actuator/prometheus>.
- Prometheus / targets: <http://localhost:9090/targets>.
- Grafana: <http://localhost:3000>.
- Dashboard **CEP Service — Observabilidade**:
  <http://localhost:3000/d/cep-service-observabilidade>.

Preencha `GRAFANA_ADMIN_USER` e `GRAFANA_ADMIN_PASSWORD` no `.env` antes de subir
o ambiente; nunca versione senhas. Para um `.env` antigo, acrescente essas duas
variaveis conforme `.env.example`, preservando as credenciais PostgreSQL existentes.
O usuario padrao e `admin`; nao existe senha padrao no Compose. As credenciais
inicializam o volume Grafana somente na primeira execucao: alterar `.env` depois
nao redefine a senha armazenada.

O Prometheus coleta `app:8080/actuator/prometheus` a cada 15s pela rede interna.
Os sete paineis mostram volume HTTP, requisicoes/s, p95, erros 5xx,
disponibilidade de scrape, memoria JVM e CPU do processo. Healthchecks e scrapes
sao excluidos dos paineis de trafego HTTP. Aguarde pelo menos dois scrapes e gere
requisicoes para visualizar taxas e percentis.

Configuracao, PromQL, geracao de trafego, validacao e limites conhecidos em
[docs/monitoramento.md](docs/monitoramento.md). Os novos servicos usam volumes
`prometheus_data` e `grafana_data`; `docker compose stop` preserva todos os volumes.

## Organizacao da consulta de CEP

O pacote base permanece `com.desafio.cep_service`:

- `domain.model`: `Cep` valida exatamente oito digitos e `Endereco` representa
  o resultado interno, sem dependencias de Spring, Jackson ou ViaCEP.
- `domain.exception`: excecoes de CEP invalido e nao encontrado.
- `application.usecase.CepService`: valida e coordena a consulta pela porta
  `application.port.out.EnderecoRepositoryPort` primeiro; somente a ausencia
  confirmada permite consultar `ConsultaCepPort` e persistir o resultado.
- `infrastructure.adapter.in.rest`: controller e DTO publico `CepResponse`.
- `infrastructure.adapter.out.viacep`: cliente HTTP e DTO externo `ViaCepResponse`.
  Somente esse adaptador interpreta o campo externo `erro`.
- `infrastructure.adapter.out.persistence`: entidade JPA, repositorio Spring Data
  e adaptador PostgreSQL, separados do modelo de dominio.
- `infrastructure.config.CepConfiguration`: monta o caso de uso e o cliente HTTP
  com injecao por construtor, mantendo o nucleo independente do Spring.

`GET /api/ceps/{cep}` preserva os campos `cep`, `logradouro`, `complemento`,
`bairro`, `localidade`, `uf` e `erro` (null no sucesso). As excecoes de validacao
e ausencia continuam sendo subclasses de `IllegalArgumentException`, com as
mesmas mensagens, agora traduzidas pelo advice REST para 400 e 404.

Os testes cobrem validacao, delegacao pela porta, ausencia, falha de integracao,
mapeamento HTTP do ViaCEP e contrato REST. O teste de contexto continua exigindo
o PostgreSQL e as variaveis de ambiente descritas abaixo.

## Tratamento HTTP e integracao ViaCEP

O `ApiExceptionHandler` centraliza respostas com exatamente `timestamp` (UTC em
ISO-8601), `status`, `error`, `message` e `path` (sem query string).
Detalhes tecnicos, corpo do provedor e stack traces nao sao enviados ao consumidor.

| Situacao | HTTP da API |
| --- | --- |
| CEP sem exatamente oito digitos, sem chamada ao ViaCEP | 400 |
| ViaCEP retorna `erro=true` ou `erro="true"` | 404 |
| Corpo ausente/vazio, JSON malformado, tipo de dado incorreto ou dados inconsistentes | 502 |
| HTTP externo inesperado, inclusive 400/401/404/500/502 | 502 |
| HTTP externo 429/503 ou falha de transporte sem timeout (conexao recusada, DNS, TLS) | 503 |
| Timeout de conexao/leitura ou HTTP externo 408/504 | 504 |
| Indisponibilidade/transitoriedade do PostgreSQL na leitura ou gravacao | 503 |
| Outras falhas de persistencia ou conflito sem registro recuperavel | 500 |
| Falha interna inesperada | 500, com mensagem generica |

Somente o indicador `erro` confirmado representa CEP inexistente. O ViaCEP real
tambem envia esse indicador como string; a desserializacao aceita estritamente
booleanos ou strings `"true"`/`"false"`, rejeitando numeros e outras strings.
O CEP retornado deve corresponder ao solicitado; localidade deve estar preenchida
e UF deve ser uma sigla valida. Logradouro, bairro e complemento podem ser vazios
ou ausentes em CEPs de localidades. Campos adicionais do provedor sao ignorados.

O adaptador traduz erros tecnicos para `FalhaConsultaCepException`, definida na
aplicacao sem dependencias HTTP. O advice decide os status HTTP. Os logs de falhas
sao emitidos uma unica vez pelo advice, com path, motivo, codigo de diagnostico
e classe da causa. Mensagens tecnicas e corpos externos nao sao registrados,
pois podem conter dados sensiveis. Erros de validacao e CEP inexistente nao geram
logs de falha de integracao.

Configuracoes em `application.properties`, com sobrescrita por ambiente:

| Propriedade | Variavel | Padrao |
| --- | --- | --- |
| `viacep.base-url` | `VIACEP_BASE_URL` | `https://viacep.com.br/ws` |
| `viacep.connect-timeout-ms` | `VIACEP_CONNECT_TIMEOUT_MS` | `2000` ms |
| `viacep.read-timeout-ms` | `VIACEP_READ_TIMEOUT_MS` | `5000` ms |

Timeouts devem ser inteiros positivos; zero ou valores negativos impedem a
inicializacao. O `RestClient` e injetado no adaptador, com timeouts configurados
na infraestrutura. O timeout de leitura limita a espera de leitura do socket,
nao o tempo total da requisicao. Nao ha politica de retry implementada na aplicacao.

Os testes do cliente e da API utilizam HTTP simulado, incluindo o fluxo completo
controller, caso de uso, adaptador e advice. Um servidor HTTP local valida o
timeout efetivo de leitura. Nenhum teste automatizado consulta o ViaCEP publico.

## PostgreSQL local

O resultado da revisao dos 15 requisitos da etapa 04 esta em
[docs/auditoria-etapa-04.md](docs/auditoria-etapa-04.md).

### Cadastro manual

`POST /api/enderecos` recebe os seis campos de endereco:

```json
{
  "cep": "12345678",
  "logradouro": "Rua manual",
  "complemento": "Casa 1",
  "bairro": "Centro",
  "localidade": "São Paulo",
  "uf": "SP"
}
```

O CEP deve conter exatamente oito digitos, a localidade deve ser preenchida
(ate 255 caracteres) e UF deve ser uma sigla brasileira valida em maiusculas.
Logradouro, complemento e bairro podem ser nulos. Entradas invalidas retornam
400 antes de acessar o banco. O cadastro manual nao consulta o ViaCEP.

O sucesso retorna 201, os seis campos persistidos (CEP no formato `12345-678`)
e `Location: /api/ceps/12345678`. O GET mantem seu contrato anterior com sete
campos, incluindo `erro: null`, e prioriza o registro armazenado manualmente.

CEP ja cadastrado retorna 409 no formato padronizado de erro. O cadastro nao
sobrescreve registros existentes, independentemente da origem. A constraint
UNIQUE protege tambem cadastros simultaneos: um retorna 201 e o outro 409.
Falhas de gravacao preservam os retornos 503/500 seguros da persistencia.
Nao foi necessario alterar a migracao ou criar novos campos no dominio/banco.

Requisitos: Java 21 e Docker Desktop com Docker Compose. A imagem oficial
`postgres:17-bookworm` permite execucao nativa em Apple Silicon, sem forcar amd64.

1. Copie `.env.example` para `.env`, se ainda nao existir.
2. Gere uma senha com `openssl rand -hex 24` e preencha `POSTGRES_PASSWORD`
   somente no `.env`. Esse arquivo e ignorado pelo Git.
3. Inicie as dependencias:

```sh
docker compose up -d --wait postgres kafka
docker compose ps
```

O Compose le `.env` automaticamente. Maven e Spring Boot nao carregam esse
arquivo automaticamente. Para executar pelo terminal, exporte as variaveis na
mesma sessao (o arquivo local deve conter apenas atribuicoes shell confiaveis):

```sh
set -a
. ./.env
set +a
./mvnw clean test
./mvnw spring-boot:run
```

### Execucao pelo IntelliJ

O `.env` usado pelo Compose nao e automaticamente repassado ao Java pelo IntelliJ.
Para executar diretamente pela IDE:

1. Abra `Run > Edit Configurations` e selecione ou crie a configuracao da classe
   `com.desafio.cep_service.CepServiceApplication`, com JDK 21 e classpath do projeto.
2. Em `Environment variables` (habilite a opcao em `Modify options` se necessario),
   configure `POSTGRES_HOST=localhost`, `POSTGRES_PORT` com a porta publicada pelo
   Compose, `POSTGRES_DB`, `POSTGRES_USER` e `POSTGRES_PASSWORD` com os valores do
   `.env` local. Nao use o texto literal `${POSTGRES_PASSWORD}` como senha.
3. Mantenha essa configuracao local: nao compartilhe nem versione uma Run
   Configuration contendo credenciais. Configure tambem as variaveis na execucao
   dos testes/Maven pela IDE; elas nao sao herdadas de outra Run Configuration.
4. Configure tambem `KAFKA_BOOTSTRAP_SERVERS=localhost:9092` (ou a porta publicada
   escolhida), pare o app Docker com `docker compose stop app` e confirme que
   PostgreSQL e Kafka estao `healthy` em `docker compose ps`. Execute
   a aplicacao. Verifique a conexao Hikari/JPA nos logs e consulte
   `http://localhost:8080/actuator/health`, que deve retornar `status: UP`.

Nao ha senha padrao na aplicacao: `POSTGRES_PASSWORD` precisa estar definida no
processo Java. O healthcheck `pg_isready` verifica disponibilidade do servidor,
mas nao substitui a validacao de autenticacao feita pela conexao JDBC real.

| Variavel | Padrao | Finalidade |
| --- | --- | --- |
| `POSTGRES_HOST` | `localhost` | Host acessado pela aplicacao no Mac |
| `POSTGRES_PORT` | `5432` | Porta local do banco |
| `POSTGRES_DB` | `cep_service` | Nome do banco |
| `POSTGRES_USER` | `cep_service` | Usuario local do banco |
| `POSTGRES_PASSWORD` | Sem padrao | Senha obrigatoria |

A porta e publicada somente em `127.0.0.1`. Se 5432 estiver ocupada, altere
`POSTGRES_PORT` antes de iniciar e exporte novamente as variaveis.

### Aplicacao dentro do Docker

O servico `app` ja declara `POSTGRES_HOST=postgres`, `POSTGRES_PORT=5432` e
`KAFKA_BOOTSTRAP_SERVERS=kafka:29092`, alem de receber banco/usuario/senha do
mesmo ambiente do PostgreSQL. Ele aguarda as duas dependencias saudaveis.
`localhost` dentro de um container aponta para o proprio container, por isso
as conexoes internas utilizam os nomes dos servicos. Consulte o inicio deste
README para subir o sistema completo com `docker compose up -d --build`.

Os dados ficam no volume nomeado `postgres_data`, gerenciado pelo Compose.
Para parar sem remover dados, use `docker compose stop postgres`.
Nao remova o volume nem execute `docker compose down -v`.
Mantenha o mesmo nome de projeto Compose para reutilizar o volume.

As variaveis `POSTGRES_DB`, `POSTGRES_USER` e `POSTGRES_PASSWORD` inicializam o
banco apenas quando o volume esta vazio. Altera-las no `.env` nao altera usuarios,
senhas ou bancos de um volume ja inicializado; essas mudancas exigem administracao
explicita do PostgreSQL, preservando os dados.

O Hibernate utiliza `ddl-auto=validate` e `open-in-view=false`. O Flyway aplica
migracoes antes da validacao JPA; `spring.flyway.clean-disabled=true` impede
limpeza via Flyway. Nao utilize `ddl-auto=update`, `create` ou `create-drop`.

### Evolucao do esquema

O starter Flyway do Spring Boot e o modulo PostgreSQL utilizam as versoes
gerenciadas pelo BOM do Spring Boot 4.1.1 (Flyway 12.4.0).
`src/main/resources/db/migration/V1__create_enderecos.sql` cria:

| Campo | Tipo / restricoes |
| --- | --- |
| `id` | BIGINT identity, chave primaria |
| `cep` | VARCHAR(8), NOT NULL, UNIQUE, CHECK de oito digitos |
| `logradouro`, `complemento`, `bairro` | TEXT, opcionais |
| `localidade` | VARCHAR(255), NOT NULL, nao vazia |
| `uf` | VARCHAR(2), NOT NULL, CHECK de duas letras maiusculas |
| `created_at`, `updated_at` | TIMESTAMP WITH TIME ZONE, NOT NULL |

A constraint `uk_enderecos_cep` fornece o indice de consulta por CEP; nenhum
indice adicional redundante e criado. O banco armazena oito digitos sem hifen;
o adaptador devolve o formato `00000-000`, preservando os sete campos da API.
Os timestamps sao preenchidos pela persistencia; `updated_at` e atualizado em
alteracoes gerenciadas pelo Hibernate. Nao ha atualizacao de enderecos nesta etapa.
Nao foi criado campo `estado`, pois ele nao pertence ao modelo atual.

O Flyway registra a V1 em `flyway_schema_history`. Depois de aplicada, nao edite
a V1: evolua com novas migracoes versionadas. Nao ha baseline automatico,
recriacao de tabelas ou remocao de dados. Um esquema preexistente incompatível
exige reconciliacao explicita, sem ativar baseline para ocultar divergencias.

### Fluxo DB-first, falhas e concorrencia

1. Validar o CEP antes de qualquer I/O.
2. Consultar PostgreSQL por CEP usando consulta derivada parametrizada.
3. Retornar o endereco local sem chamada externa quando presente.
4. Somente na ausencia confirmada, consultar ViaCEP.
5. Gravar o endereco valido e responder somente depois do commit.

Falha de banco nunca vira `Optional.empty()` e nunca autoriza fallback externo.
Falha de gravacao tambem nao retorna um endereco que nao foi persistido. O
adaptador traduz falhas para `FalhaPersistenciaEnderecoException`, distinguindo
LEITURA/GRAVACAO e indisponibilidade/erro/conflito nao recuperado. O advice
registra operacao, motivo e classe da causa, sem expor SQL ou detalhes internos
nas respostas. As politicas ViaCEP 400/404/502/503/504 permanecem preservadas.

O caso de uso nao e transacional. O adaptador utiliza `TransactionTemplate` com
`REQUIRES_NEW` para cada leitura (read-only) e gravacao (`saveAndFlush`). Nenhuma
transacao permanece aberta durante o HTTP externo.

Duas consultas concorrentes podem chamar ViaCEP. A constraint UNIQUE arbitra a
gravacao. Somente SQLSTATE 23505 associado a `uk_enderecos_cep` e traduzido para
`EnderecoJaCadastradoException`, depois que o rollback ja terminou. O caso de uso
reconsulta em nova transacao e retorna o registro vencedor. Outras violacoes de
integridade nao sao tratadas como concorrencia. Se a recuperacao nao encontrar o
registro, retorna erro controlado; se a releitura falhar, propaga a falha do banco.
Nao ha lock global nem sincronizacao em memoria na aplicacao.

### Testes de persistencia

Execute `./mvnw clean test` com as variaveis PostgreSQL exportadas, conforme acima.
Os testes de integracao usam o PostgreSQL Docker existente e criam um schema
isolado `cep_it_<uuid>` por execucao, sem apagar dados, tabelas ou volumes.
Esses schemas de teste permanecem no banco, respeitando a politica de nao remocao;
use um banco dedicado a testes em execucoes recorrentes. A credencial local
precisa permitir a criacao desse schema. Os testes unitarios usam mocks.

A cobertura inclui migracao reproduzivel, esquema validado, round-trip JPA,
consulta parametrizada, ausencia de transacao durante ViaCEP e concorrencia real
com rollback e recuperacao. Nenhum teste automatizado depende do ViaCEP publico.

Na validacao manual desta etapa, `01001000` foi persistido e retornado novamente
com resposta identica mesmo com o cliente externo apontado temporariamente para
uma porta inacessivel. O registro foi preservado. A URL configurada no arquivo
continua sendo a URL publica original.

A auditoria Kafka e a migracao V2 estao implementadas; a suite completa exige
PostgreSQL e Kafka locais saudaveis. Enderecos locais nao possuem politica de
expiracao/atualizacao automatica nesta etapa.
