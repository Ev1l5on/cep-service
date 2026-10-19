# Etapas 05 e 06: eventos de consulta e auditoria assincrona

[README](../README.md) · [Diagramas](arquitetura.md) · [Decisoes](decisoes-arquiteturais.md)

Documento operacional da auditoria. As evidencias e o inventario ao final sao
registros historicos das etapas 05/06; configuracoes de execucao refletem o Compose atual.

## Fluxo e fronteiras

`GET /api/ceps/{cep}` continua validando a entrada e consultando PostgreSQL
antes do ViaCEP. Depois de obter um endereco confirmado, `CepService` cria um
`ConsultaCepEvento` e chama `PublicarConsultaPort`. Cada GET bem-sucedido cria um
UUID novo. Falhas de consulta nao publicam eventos; o POST manual nao publica.
Os contratos REST existentes permanecem intactos.

`KafkaConsultaPublisher` enfileira a tarefa em um executor exclusivo, com dois
workers e capacidade padrao de 1000 tarefas. A thread HTTP nao chama KafkaTemplate,
nao aguarda metadados do broker e nao espera confirmacao ou persistencia do log.
O worker serializa JSON e envia com a chave CEP de oito digitos. O callback trata
sucesso/falha do broker; falhas imediatas, callbacks e rejeicao da fila sao
diagnosticados sem alterar o resultado HTTP. A porta tambem tem protecao no caso
de uso para excecoes imediatas inesperadas de sua implementacao.

O listener recebe strings, faz parsing para o contrato conhecido e delega a
`RegistrarConsultaService`, que valida versao, campos essenciais e consistencia
do CEP/endereco. O listener verifica tambem a chave Kafka. A persistencia fica
em `ConsultaLogRepositoryPort` e `ConsultaLogPersistenceAdapter`, com uma transacao
local curta. O dominio e os casos de uso nao dependem de KafkaTemplate.

## Contrato JSON v1

```json
{
  "eventId": "82fe548c-e29b-4395-9105-99749af03dd8",
  "eventVersion": 1,
  "cep": "01001000",
  "consultedAt": "2026-10-19T01:07:08.390549Z",
  "source": "DATABASE",
  "responseData": {
    "cep": "01001-000",
    "logradouro": "Praça da Sé",
    "complemento": "lado ímpar",
    "bairro": "Sé",
    "localidade": "São Paulo",
    "uf": "SP"
  }
}
```

O instante e UTC/ISO-8601. `source` aceita DATABASE e VIACEP. A origem descreve
o endereco efetivamente retornado: recuperacao de registro concorrente e DATABASE,
mesmo se um resultado ViaCEP tiver sido descartado nessa disputa. O snapshot
contem exatamente os seis campos de Endereco, sem o campo REST de compatibilidade
`erro`. Campos desconhecidos, tipos incompativeis e versoes nao suportadas sao
rejeitados pelo consumidor. O evento nao inclui credenciais nem detalhes tecnicos.

## Kafka local e configuracao

O Compose usa `apache/kafka:4.1.1`, confirmado como linux/arm64, em modo KRaft.
O broker/controller unico e o volume `kafka_data` sao independentes do PostgreSQL.
O cluster ID deve permanecer estavel para reutilizar esse volume.

| Uso | Bootstrap |
| --- | --- |
| Aplicacao no Mac/IntelliJ | `localhost:9092` |
| Aplicacao no servico app da rede Compose | `kafka:29092` |

O listener EXTERNAL e publicado somente em 127.0.0.1. `KAFKA_PORT` e
`KAFKA_ADVERTISED_HOST` controlam a porta/endereco anunciados ao host. Se alterar
a porta, atualize tambem KAFKA_BOOTSTRAP_SERVERS no processo Java. O listener
INTERNAL continua usando kafka:29092. O servico app ja declara
POSTGRES_HOST=postgres e POSTGRES_PORT=5432 automaticamente. Credenciais
PostgreSQL e Grafana exigidas pelo Compose estao descritas no README.

| Variavel | Padrao |
| --- | --- |
| KAFKA_BOOTSTRAP_SERVERS | localhost:9092 |
| KAFKA_AUDIT_TOPIC | cep-consultas-v1 |
| KAFKA_AUDIT_DLT_TOPIC | cep-consultas-v1.DLT |
| KAFKA_AUDIT_GROUP_ID | cep-service-auditoria-v1 |
| KAFKA_AUDIT_QUEUE_CAPACITY | 1000, positivo |
| KAFKA_AUDIT_MAX_RETRIES | 2, permitido entre 0 e 10 |
| KAFKA_AUDIT_RETRY_INTERVAL_MS | 1000, positivo |

KafkaAdmin cria os dois topicos com tres particoes e fator de replicacao 1.
Topico e DLT devem ser distintos e manter quantidades de particoes compativeis;
a recuperacao usa a mesma particao na DLT. A DLT tem retencao de 14 dias; o
topico principal herda a retencao do broker. Auto-criacao no broker esta desligada.

O producer usa acks=all, idempotencia Kafka, max.block.ms=1000,
request.timeout.ms=5000 e delivery.timeout.ms=15000. Estes limites nao significam
garantia de entrega. O max.block.ms limita espera do worker por metadados/buffer,
sem colocar essa espera na thread HTTP. KafkaAdmin nao exige broker disponivel
para permitir a inicializacao, mas pode aguardar seu timeout de operacao (10s).

```sh
docker compose config --quiet
docker compose up -d --wait postgres kafka
docker compose ps
set -a
. ./.env
set +a
./mvnw clean test
# Para executar a JVM no host sem conflito com o app Docker:
docker compose stop app
./mvnw spring-boot:run
```

Compose interpola .env; Maven, Spring e IntelliJ precisam receber essas variaveis
separadamente, como documentado no README. Nao versione o .env real.

## Persistencia e idempotencia

`V2__create_consulta_logs.sql` acrescenta a tabela sem alterar V1:

| Coluna | Definicao |
| --- | --- |
| id | BIGINT identity, chave primaria |
| event_id | UUID obrigatorio, UNIQUE |
| event_version | INTEGER obrigatorio, positivo |
| cep | VARCHAR(8), oito digitos, obrigatorio |
| consulted_at | TIMESTAMP WITH TIME ZONE, obrigatorio |
| source | VARCHAR(8), DATABASE ou VIACEP |
| response_data | JSONB, objeto obrigatorio |
| created_at | TIMESTAMP WITH TIME ZONE, default CURRENT_TIMESTAMP |

O adaptador usa JdbcTemplate ja disponivel no starter JPA, com parametros e
`INSERT ... ON CONFLICT (event_id) DO NOTHING` dentro de TransactionTemplate.
Isso implementa idempotencia atomica sem reutilizar transacao rollback-only.
O repositorio de enderecos continua utilizando Spring Data JPA. Nao foram
adicionadas dependencias para essa implementacao.

Reentrega de um eventId mantem uma linha. GETs diferentes do mesmo CEP geram IDs
diferentes e logs separados. O primeiro snapshot gravado para um UUID e imutavel:
reutilizar esse UUID com outro payload nao atualiza a linha. O PostgreSQL armazena
instantes com precisao de microssegundos.

## Retry, confirmacao e DLT

Auto-commit Kafka esta desligado e o container usa AckMode.RECORD com commits
sincronos. O listener retorna somente depois do commit PostgreSQL. Se o processo
falhar entre o commit no banco e o commit do offset, a reentrega sera idempotente.
Nao ha transacao distribuida entre Kafka e PostgreSQL.

DefaultErrorHandler aplica duas novas tentativas, com intervalo de 1s, apos a
primeira falha: tres entregas no total. Erros estruturais, de chave, versao ou
integridade de dados vao diretamente a DLT. Outras falhas, inclusive temporarias
do banco, usam o retry limitado; se esgotado, seguem para a DLT.

DeadLetterPublishingRecoverer exige confirmacao do envio para a DLT antes de
considerar o registro recuperado. Essa espera ocorre no consumidor, nunca no HTTP.
Se o envio a DLT falhar, o offset nao e confirmado; a recuperacao continua sendo
tentada. O limite de processamento nao implica descartar um registro quando a
propria DLT esta indisponivel. Nesse caso o consumo fica retido ate recuperacao.

Falhas tecnicas sao sanitizadas antes de atravessar o listener. Os logs contem
topic, partition, offset, tentativa, classe de erro; no producer incluem eventId,
CEP e etapa da falha. Nao se registram corpos externos, SQL, credenciais ou tokens.
A DLT preserva a mensagem original e metadados de origem, com header seguro
audit-failure-type; mensagens e stack traces tecnicos nao sao adicionados aos
headers de excecao. O listener padrao de erro do producer foi desativado para
evitar logs duplicados/payloads; o adaptador cuida dos callbacks.

### Operacao da DLT

Inspecione lag e DLT antes da expiracao de sua retencao:

```sh
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server kafka:29092 --describe --group cep-service-auditoria-v1
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka:29092 --topic cep-consultas-v1.DLT \
  --from-beginning --max-messages 1 --timeout-ms 10000
```

A DLT nao e consumida automaticamente, evitando ciclos de poison messages.
Apos diagnosticar/corrigir a causa, um operador deve republicar o JSON revisado
no topico original, com chave CEP e preservando eventId para a mesma ocorrencia.
Use um UUID novo para uma ocorrencia semanticamente nova. Revise versoes nao
suportadas antes do replay; nao reenvie em loop uma mensagem ainda invalida.

## Limites de confiabilidade

Esta e publicacao assincrona direta, best effort ate o aceite pelo Kafka.
Rejeicao da fila, falha de metadados/envio, expiracao do delivery timeout,
interrupcao abrupta ou crash entre resposta e envio podem perder eventos.
As falhas conhecidas geram log seguro e o HTTP 200 e preservado; nao existe
spool duravel ou Transactional Outbox. Mesmo um encerramento gracioso nao
substitui uma garantia duravel. Idempotencia do producer nao resolve essa janela.

Depois do aceite pelo Kafka, o processamento e at-least-once, com idempotencia
no banco e DLT para falhas nao recuperadas. Ainda dependemos de retencao,
monitoramento operacional, disponibilidade e durabilidade dos volumes.
Uma falha na DLT pode bloquear consumo. O broker unico local nao oferece alta
disponibilidade; PLAINTEXT foi escolhido para a rede local de desenvolvimento.
Producao exige avaliacao de replicacao, TLS/SASL, alertas, capacidade e politica
de replay/retencao. Outbox pode ser uma etapa futura se perda zero for requisito.

## Evidencias de validacao

- `./mvnw clean test`: 207 testes, zero falhas/erros/ignorados, BUILD SUCCESS.
- Todos os 153 cenarios anteriores continuam aprovados.
- Dez testes novos usam Kafka e PostgreSQL reais em topicos/schema isolados;
  cobrem origem DATABASE/VIACEP, POST sem publicacao, idempotencia, retry, DLT,
  falha de envio DLT e ausencia de confirmacao prematura de offsets.
- Testes unitarios cobrem contrato JSON UTC, validacao, fila limitada,
  callbacks/falhas imediatas e HTTP 200 mesmo com falha de publicacao.
- Testes nao dependem do ViaCEP publico. Topicos/schemas isolados permanecem
  preservados; use recursos dedicados para execucoes recorrentes.
- Validacao manual: GET 01001000 originou DATABASE, com eventId
  82fe548c-e29b-4395-9105-99749af03dd8. O mesmo evento foi republicado; duas
  entregas confirmadas mantiveram uma linha no banco.
- GET real 01310100 originou VIACEP, com eventId
  9b0a6ff2-0f4c-4162-8cc2-3c8f14b6a64e. Snapshot e horario foram persistidos.
- O grupo confirmou tres mensagens, com lag zero na particao utilizada;
  PostgreSQL manteve duas linhas, uma por UUID. Flyway V1/V2 validadas.

Nenhum dado/volume anterior foi removido; nao houve commit, push ou deploy.

## Inventario de arquivos

Criados em `src/main/java/com/desafio/cep_service/`:

```text
application/event/ConsultaCepEvento.java
application/exception/EventoConsultaInvalidoException.java
application/port/out/PublicarConsultaPort.java
application/port/out/ConsultaLogRepositoryPort.java
application/usecase/RegistrarConsultaService.java
infrastructure/adapter/out/messaging/KafkaConsultaPublisher.java
infrastructure/adapter/out/persistence/ConsultaLogPersistenceAdapter.java
infrastructure/adapter/in/messaging/ConsultaAuditoriaListener.java
infrastructure/adapter/in/messaging/FalhaProcessamentoAuditoriaException.java
infrastructure/config/KafkaAuditoriaConfiguration.java
infrastructure/serialization/ConsultaEventoJson.java
```

Tambem criados:

```text
src/main/resources/db/migration/V2__create_consulta_logs.sql
docs/auditoria-kafka.md
```

Novos testes em `src/test/java/com/desafio/cep_service/`:

```text
application/usecase/AuditoriaConsultaServiceTest.java
application/usecase/RegistrarConsultaServiceTest.java
infrastructure/adapter/out/messaging/KafkaConsultaPublisherTest.java
infrastructure/adapter/in/messaging/ConsultaAuditoriaListenerTest.java
infrastructure/adapter/in/messaging/AuditoriaKafkaIntegrationTest.java
infrastructure/config/KafkaAuditoriaConfigurationTest.java
```

Alterados: `compose.yaml`, `.env.example`, `README.md`,
`src/main/resources/application.properties`, `application/usecase/CepService.java`
e `infrastructure/config/CepConfiguration.java`. Os testes anteriores
`CepServiceApplicationTests`, `CepServiceTest`, `CepControllerTest` e
`EnderecoPersistenceIntegrationTest` receberam a nova dependencia por construtor,
isolamento dos listeners nao utilizados e verificacao da versao atual V2.
Nenhum arquivo foi removido e a migracao V1 foi preservada.
