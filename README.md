# CEP Service

## Organizacao da consulta de CEP

O pacote base permanece `com.desafio.cep_service`:

- `domain.model`: `Cep` valida exatamente oito digitos e `Endereco` representa
  o resultado interno, sem dependencias de Spring, Jackson ou ViaCEP.
- `domain.exception`: excecoes de CEP invalido e nao encontrado.
- `application.usecase.CepService`: valida e coordena a consulta pela porta
  `application.port.out.ConsultaCepPort`, que retorna `Optional<Endereco>`.
- `infrastructure.adapter.in.rest`: controller e DTO publico `CepResponse`.
- `infrastructure.adapter.out.viacep`: cliente HTTP e DTO externo `ViaCepResponse`.
  Somente esse adaptador interpreta o campo externo `erro`.
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

Requisitos: Java 21 e Docker Desktop com Docker Compose. A imagem oficial
`postgres:17-bookworm` permite execucao nativa em Apple Silicon, sem forcar amd64.

1. Copie `.env.example` para `.env`, se ainda nao existir.
2. Gere uma senha com `openssl rand -hex 24` e preencha `POSTGRES_PASSWORD`
   somente no `.env`. Esse arquivo e ignorado pelo Git.
3. Inicie o banco:

```sh
docker compose up -d --wait postgres
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
4. Confirme que `docker compose ps` mostra o PostgreSQL como `healthy` e execute
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

A configuracao JDBC existente tambem aceita `POSTGRES_HOST=postgres`.
Quando a aplicacao estiver em um container na mesma rede Compose do banco,
injete no container da aplicacao:

```text
POSTGRES_HOST=postgres
POSTGRES_PORT=5432
POSTGRES_DB=<mesmo banco do PostgreSQL>
POSTGRES_USER=<mesmo usuario do PostgreSQL>
POSTGRES_PASSWORD=<mesma senha, fornecida pelo ambiente>
```

Nesse contexto, `postgres` e o nome do servico e `5432` e a porta interna,
independentemente da porta publicada no Mac. `localhost` dentro de um container
aponta para o proprio container. Interpolar `.env` no Compose nao injeta todas as
suas variaveis automaticamente: o futuro servico da aplicacao deve declarar seu
`environment` explicitamente e aguardar o banco saudavel com
`depends_on: postgres: condition: service_healthy` na estrutura YAML apropriada.
O Compose atual inclui somente o banco; a imagem da aplicacao nao faz parte desta etapa.

Os dados ficam no volume nomeado `postgres_data`, gerenciado pelo Compose.
Para parar sem remover dados, use `docker compose stop postgres`.
Nao remova o volume nem execute `docker compose down -v`.
Mantenha o mesmo nome de projeto Compose para reutilizar o volume.

As variaveis `POSTGRES_DB`, `POSTGRES_USER` e `POSTGRES_PASSWORD` inicializam o
banco apenas quando o volume esta vazio. Altera-las no `.env` nao altera usuarios,
senhas ou bancos de um volume ja inicializado; essas mudancas exigem administracao
explicita do PostgreSQL, preservando os dados.

O Hibernate esta configurado com `ddl-auto=none`: a aplicacao nao cria nem apaga
tabelas automaticamente. Esta etapa prepara somente o PostgreSQL; Kafka e a
persistencia de enderecos/logs ainda nao foram implementados.

### Evolucao do esquema

Sem entidades ou esquema de negocio definido, `ddl-auto=none` permite inicializar
JPA sem criar tabelas. `spring.jpa.open-in-view=false` permanece ativo.

Na etapa de persistencia de enderecos, adote Flyway com suporte PostgreSQL e
migracoes SQL versionadas em `src/main/resources/db/migration`. Na etapa de logs
Kafka, acrescente migracoes para as tabelas de auditoria. Defina primeiro os
modelos e o esquema; nenhuma tabela especulativa e necessaria agora.
Com as migracoes e entidades implementadas, avalie `ddl-auto=validate` para
conferir a correspondencia entre mapeamento e esquema. Evite `create`,
`create-drop` e atualizacoes automaticas como estrategia permanente de esquema.
