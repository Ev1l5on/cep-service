# Decisões arquiteturais e trade-offs

[README](../README.md) · [Diagramas](arquitetura.md)

O objetivo foi entregar um desafio funcional e verificável, com fronteiras claras e complexidade proporcional. As decisões abaixo descrevem a implementação existente na revisão `188040b`. Itens marcados como evolução não estão implementados.

## 1. Arquitetura hexagonal e SOLID

**Implementado.** O pacote base `com.desafio.cep_service` está dividido em domínio, aplicação e infraestrutura. `Cep` encapsula a regra dos oito dígitos; `Endereco` é um record de seis campos, não uma entidade JPA e não contém toda a validação de cadastro. `CepService`, `CadastrarEnderecoService` e `RegistrarConsultaService` coordenam operações distintas.

As portas de saída são pequenas e orientadas às necessidades dos casos de uso:

| Porta | Operações | Implementação |
| --- | --- | --- |
| `ConsultaCepPort` | `buscar` | `ViaCepClient` |
| `EnderecoRepositoryPort` | `buscarPorCep`, `salvar` | `EnderecoPersistenceAdapter` |
| `PublicarConsultaPort` | `publicar` | `KafkaConsultaPublisher` |
| `ConsultaLogRepositoryPort` | `salvar` | `ConsultaLogPersistenceAdapter` |

Aplicação e domínio não importam Spring, JPA, Kafka ou classes HTTP. A infraestrutura depende das portas; `CepConfiguration` fornece implementações por injeção de construtor. Os adaptadores de entrada chamam os casos de uso concretos. Não foi criada uma interface por classe, nem uma camada adicional apenas para reproduzir métodos.

| Princípio | Aplicação concreta e limite |
| --- | --- |
| SRP | Controller mapeia HTTP; caso de uso orquestra; adaptador traduz tecnologia; advice converte exceções em respostas. O cadastro e o registro de auditoria têm validações próprias. |
| OCP | Outra implementação da mesma porta pode substituir ViaCEP/persistência/publicação sem reescrever a orquestração. Uma regra nova ainda pode exigir alteração do caso de uso; não se promete extensão sem mudanças para qualquer requisito. |
| LSP | O contrato de `Optional.empty()` é ausência confirmada, nunca indisponibilidade. Implementações substitutas devem preservar essa distinção e a confirmação de gravação antes do retorno. |
| ISP | Quatro portas específicas evitam que cadastro dependa de mensageria ou que consulta dependa de detalhes do listener. |
| DIP | Casos de uso dependem de interfaces da aplicação, não de `KafkaTemplate`, `JpaRepository` ou `RestClient`. |

**Trade-off.** O modelo é pequeno e usa funções de validação nas fronteiras, sem agregar frameworks de DDD ou abstrações de Strategy/Factory/CQRS. O fallback diagnóstico de `CepService` usa `System.Logger` do JDK para proteger o retorno contra uma implementação de publicação que lance exceção; não introduz dependência de logging externo no núcleo. O evento gera UUID/instante diretamente; abstrair relógio só se justificaria com requisitos temporais adicionais.

**Evidências:** [casos de uso e portas](../src/main/java/com/desafio/cep_service/application/), [composição Spring](../src/main/java/com/desafio/cep_service/infrastructure/config/CepConfiguration.java), [testes dos casos de uso](../src/test/java/com/desafio/cep_service/application/usecase/).

## 2. Persistência prioritária e significado do cadastro manual

**Implementado.** `CepService.buscar` valida primeiro, consulta `EnderecoRepositoryPort` e retorna imediatamente o registro local. Apenas ausência confirmada permite ViaCEP. Resultado externo válido precisa ser persistido antes de retornar sucesso. Falha de leitura não vira ausência; falha de gravação não retorna endereço não confirmado.

`CadastrarEnderecoService.cadastrar` tenta inserir, sem consultar ViaCEP e sem publicar evento de consulta. O banco arbitra duplicidade. O GET prioriza o manual porque prioriza todo registro local.

**Limite relevante.** Não há campo de origem em `enderecos`, endpoint de atualização nem uma comparação entre cadastro manual e externo. Se ViaCEP já foi persistido, POST manual retorna 409. A prioridade não significa que um POST pode substituir dados existentes: o primeiro commit vence. `source=DATABASE` no evento descreve a fonte do retorno atual, não se o endereço nasceu manualmente.

**Trade-off.** Regra simples, previsível e sem sobrescrita silenciosa. Em contrapartida, endereços obtidos externamente não expiram nem são atualizados automaticamente.

**Evolução.** Definir uma política explícita de correção manual, proveniência, autorização, atualização concorrente e expiração. Isso exige contrato e migração novos; não está escondido em um upsert atual.

**Evidências:** [CepService](../src/main/java/com/desafio/cep_service/application/usecase/CepService.java) e testes `cadastroManualPeloPostEhPriorizadoNoGetSemViaCep`, `cadastroDuplicadoRetorna409EPreservaEnderecoOriginal` em [EnderecoPersistenceIntegrationTest](../src/test/java/com/desafio/cep_service/infrastructure/adapter/out/persistence/EnderecoPersistenceIntegrationTest.java).

## 3. Validação, SQL Injection e contratos de dados

**Implementado.** O CEP de entrada aceita exatamente oito dígitos, mantendo zeros à esquerda, antes de banco ou HTTP. No POST, localidade deve estar preenchida e ter no máximo 255 caracteres; UF deve estar na lista de siglas brasileiras. Campos opcionais podem ser nulos. Essa validação não confirma a existência postal do CEP manual.

O domínio `Endereco`, os DTOs REST, `ViaCepResponse` e `EnderecoEntity` são separados. O GET possui sete campos por compatibilidade (`erro: null`); POST e snapshots possuem seis. Endereços são armazenados com CEP de oito caracteres e retornados com hífen.

`EnderecoJpaRepository.findByCep` usa binding de parâmetros derivado do Spring Data JPA. O SQL de auditoria usa `JdbcTemplate` com placeholders `?`, inclusive para JSONB. Não há concatenação de entrada do usuário na construção de SQL de produção. Os testes também exercitam apóstrofos e entrada semelhante a SQL como dados literais.

**Trade-off.** Validação e parametrização são proteções complementares. Escapar strings manualmente não substitui parâmetros, e validar apenas CEP não protegeria todos os campos do cadastro.

**Limites.** Não foram implementados autenticação, autorização, limitação de requisições ou uma política de tamanho para cada campo textual opcional. A exposição está limitada ao ambiente local.

**Evidências:** [Cep](../src/main/java/com/desafio/cep_service/domain/model/Cep.java), [repositório JPA](../src/main/java/com/desafio/cep_service/infrastructure/adapter/out/persistence/EnderecoJpaRepository.java), [adaptador de auditoria](../src/main/java/com/desafio/cep_service/infrastructure/adapter/out/persistence/ConsultaLogPersistenceAdapter.java) e testes `consultaDerivadaUsaParametroSemInterpretarSqlDaEntrada` / `cadastroComApostrofosEhPersistidoComoDadoSemExecutarSql`.

## 4. Migrações, concorrência e fronteiras transacionais

**Implementado.** Flyway V1 cria `enderecos`; V2 cria `consulta_logs`. Constraints UNIQUE de CEP e UUID são a autoridade de integridade, inclusive entre múltiplos processos. Seus índices evitam índices adicionais redundantes para essas duas operações. JPA valida `EnderecoEntity` com `ddl-auto=validate`; a tabela de auditoria é criada/validada pelas migrações e exercitada por integração, não por uma entidade JPA fictícia.

O adaptador de endereço usa `TransactionTemplate` com `REQUIRES_NEW` para leitura read-only e gravação com `saveAndFlush`. O caso de uso não é transacional: leitura termina, HTTP externo acontece e outra transação grava. Isso evita reter conexão/transação durante espera de rede.

Duas consultas de CEP ausente podem chamar ViaCEP. Uma grava; a outra pode receber SQLSTATE `23505` da constraint específica `uk_enderecos_cep`. O adaptador traduz o conflito **depois que o rollback terminou**. O caso de uso reconsulta em nova transação e retorna o vencedor. Falha na recuperação continua sendo falha, não autorização para devolver um endereço externo descartado.

No POST, duplicidade resulta em 409, sem recuperação que simule criação. Em uma disputa entre POST manual e GET externo, vence o commit confirmado primeiro; há teste em que o manual é inserido enquanto o GET aguarda e é recuperado pelo GET.

**Trade-off.** A constraint resolve unicidade, não elimina chamadas externas simultâneas. Evitar chamadas duplicadas exigiria coordenação adicional; um lock global em memória não resolveria múltiplas instâncias. A abordagem atual preserva simplicidade e integridade.

**Evolução.** Medir contenção antes de adicionar coordenação. Definir timeout/budget de banco e avaliar índices de relatórios conforme consultas e volume, sem modificar migrações já aplicadas.

**Evidências:** [persistência de endereços](../src/main/java/com/desafio/cep_service/infrastructure/adapter/out/persistence/EnderecoPersistenceAdapter.java), [V1](../src/main/resources/db/migration/V1__create_enderecos.sql), [V2](../src/main/resources/db/migration/V2__create_consulta_logs.sql) e integração `primeiraConsultaPersisteESegundaNaoAcionaViaCepSemTransacaoDuranteHttp`, `conflitoRealFazRollbackENovaTransacaoConsegueLer`, `duasRequisicoesConcorrentesRetornamVencedorSemDuplicarRegistro`.

## 5. Exceções centralizadas e resiliência ViaCEP

**Implementado.** Exceções de domínio/aplicação não conhecem códigos HTTP. `ViaCepClient` traduz falhas técnicas para `FalhaConsultaCepException`; o adaptador PostgreSQL distingue leitura/gravação e indisponibilidade/outros erros. `ApiExceptionHandler` decide 400/404/409/500/502/503/504 e padroniza `timestamp`, `status`, `error`, `message`, `path`.

Apenas o indicador ViaCEP `erro=true` ou `erro="true"` representa ausência. Timeout é 504; transporte indisponível e HTTP externo 429/503 são 503; corpo vazio, JSON incompatível, CEP inconsistente e HTTP externo inesperado são 502. HTTP externo 408/504 também resulta em 504. HTTP 404 do provedor, sem o indicador contratado, é resposta inesperada 502.

O `RestClient` é injetado e configura conexão em 2s e leitura em 5s, sobrescrevíveis por ambiente. Os valores devem ser positivos. Parsing rejeita coerções incompatíveis; dados essenciais são verificados e atributos extras do ViaCEP são ignorados. Não há retry de negócio, circuit breaker ou fallback de banco indisponível para ViaCEP.

**Trade-off.** Timeouts limitam operações de conexão/leitura, não estabelecem um deadline total de todo o GET. Retries indiscriminados amplificariam latência e carga de um provedor degradado. O código não promete sucesso sem persistência quando o banco falha.

Os logs de falhas controladas usam campos de diagnóstico, operação e classe de causa, sem registrar payload externo, SQL ou mensagem técnica no advice. São logs textuais com campos identificáveis, não uma implementação geral de logging JSON/tracing distribuído. Logs de bibliotecas ainda precisam de revisão operacional antes de uma exposição de produção.

**Evidências:** [ViaCepClient](../src/main/java/com/desafio/cep_service/infrastructure/adapter/out/viacep/ViaCepClient.java), [ApiExceptionHandler](../src/main/java/com/desafio/cep_service/infrastructure/adapter/in/rest/ApiExceptionHandler.java), [ViaCepClientTest](../src/test/java/com/desafio/cep_service/infrastructure/adapter/out/viacep/ViaCepClientTest.java) e [CepControllerTest](../src/test/java/com/desafio/cep_service/infrastructure/adapter/in/rest/CepControllerTest.java).

## 6. Kafka e desacoplamento da auditoria

**Implementado.** GET concluído cria UUID novo, versão 1, CEP, instante UTC, origem e snapshot dos seis campos. Falhas de consulta e POST não publicam eventos. `PublicarConsultaPort` mantém o caso de uso independente de Kafka; o adaptador enfileira em dois workers, com fila padrão de 1000 tarefas.

A thread HTTP não chama `KafkaTemplate.send` nem espera metadados, `.get()`, `.join()` ou commit da auditoria. O worker usa o CEP como chave e trata exceção imediata e callback. Rejeição da fila e falha de publicação são diagnosticadas sem converter uma consulta válida em erro HTTP.

**Trade-off.** `KafkaTemplate.send` sozinho não bastaria para isolar o HTTP: a chamada pode esperar metadados/buffer. O executor limita essa espera aos workers e limita a memória da fila. Fila cheia usa rejeição diagnosticada, não bloqueio indefinido nem execução do envio na thread chamadora.

Há idempotência no producer Kafka e `acks=all`, com timeouts configurados. O broker local tem uma réplica: isso não cria redundância física. A chave favorece mesma partição por CEP, mas múltiplos workers/requisições concorrentes não garantem ordem cronológica de criação; a ordem Kafka é a ordem efetiva de gravação em cada partição.

O contrato é versionado, mas não é automaticamente compatível com qualquer evolução: `ConsultaEventoJson` rejeita campos desconhecidos e o serviço aceita somente versão 1. Mudanças exigem uma estratégia coordenada de compatibilidade/tópico/consumidores.

**Evidências:** [producer](../src/main/java/com/desafio/cep_service/infrastructure/adapter/out/messaging/KafkaConsultaPublisher.java), [configuração](../src/main/java/com/desafio/cep_service/infrastructure/config/KafkaAuditoriaConfiguration.java), testes `httpRetornaAntesDoEnvioEDaConfirmacaoKafka`, `filaCheiaNaoPropagaErroERegistraDiagnosticoSeguro` e [contrato operacional](auditoria-kafka.md).

## 7. Consumer, retry, DLT e idempotência

**Implementado.** O listener faz parsing de contrato fixo, confere chave/CEP e delega a `RegistrarConsultaService`. O caso de uso valida e chama `ConsultaLogRepositoryPort`; o adaptador grava com `INSERT ... ON CONFLICT (event_id) DO NOTHING` em transação curta `REQUIRES_NEW`. O primeiro snapshot gravado para um UUID permanece; uma reentrega não sobrescreve a linha, mesmo se trouxer payload diferente.

Auto-commit Kafka está desabilitado. `AckMode.RECORD` e commits síncronos permitem confirmar depois que o listener retorna, após o commit PostgreSQL. Se o banco confirma e o processo cai antes do offset, há reentrega segura pela unicidade do UUID. São duas confirmações locais, não uma transação distribuída.

Falhas recuperáveis são tentadas até três vezes por padrão: primeira entrega e dois retries com intervalo de 1s. JSON/versão/chave inválidos e violações de integridade do snapshot não têm retry e seguem para DLT. Falhas persistentes esgotam tentativas e também seguem para DLT.

O recoverer envia à mesma partição de `cep-consultas-v1.DLT`, aguarda confirmação e só então considera recuperado. Se o envio falha, não permite confirmar o offset; a recuperação continua sendo tentada. A espera de confirmação DLT está no consumidor, não no GET. Metadados de origem e o payload são preservados; headers técnicos de mensagem/stack trace não são adicionados pelo tratamento configurado.

**Trade-off.** Retry bloqueante pode atrasar outros eventos da mesma partição. Uma falha longa do banco pode levar mensagens válidas à DLT; a DLT evita descarte silencioso, mas requer acompanhamento e operação antes de expirar (14 dias). Falha da própria DLT pode reter consumo. Não há replay automático, consumidor DLT ou tela operacional.

A idempotência é por evento, não por CEP: dois GETs devem produzir duas linhas. Após aceite pelo broker, usa-se processamento at-least-once com idempotência de gravação, limitado por retenção e durabilidade. Não há garantia exactly-once de ponta a ponta.

**Evidências:** [listener](../src/main/java/com/desafio/cep_service/infrastructure/adapter/in/messaging/ConsultaAuditoriaListener.java), [persistência de logs](../src/main/java/com/desafio/cep_service/infrastructure/adapter/out/persistence/ConsultaLogPersistenceAdapter.java) e [integração Kafka](../src/test/java/com/desafio/cep_service/infrastructure/adapter/in/messaging/AuditoriaKafkaIntegrationTest.java): duplicata, retry, DLT, persistência pendente e envio DLT indisponível.

## 8. Janela de perda do producer e possível outbox

**Limitação implementada e assumida.** O envio direto com fila em memória não grava uma intenção durável. Pode haver perda ao rejeitar tarefa, expirar envio, ocorrer indisponibilidade prolongada ou cair o processo depois de obter o endereço e antes de o broker aceitar o evento. Logar a falha não recupera o evento. A idempotência do producer não resolve essa janela.

**Evolução, não implementada.** Se o requisito exigir intenção de auditoria durável antes do sucesso HTTP, uma transactional outbox pode gravar o evento no PostgreSQL e um publicador posterior encaminhá-lo ao Kafka. Para GET que insere endereço, intenção e endereço poderiam compartilhar uma transação local. Para GET que só lê endereço, seria necessário adicionar a gravação da intenção de auditoria à operação, com custo de escrita/latência no caminho HTTP.

Outbox exige relay/CDC ou polling, controle de concorrência, reentrega, limpeza/retenção e monitoramento de backlog. Não elimina duplicatas na passagem ao Kafka; o consumer idempotente continua necessário. Também não oferece atomicidade entre commit de banco e recebimento da resposta pelo cliente: um cliente pode repetir uma consulta cuja resposta se perdeu. A decisão depende da garantia de negócio, não apenas da preferência por um padrão.

## 9. Observabilidade e análise de incidentes

**Implementado.** Actuator expõe somente saúde e Prometheus, sem detalhes de health. Prometheus coleta a cada 15s na rede Docker; Grafana provisiona sete painéis HTTP/JVM. O p95 usa buckets de histograma reais e os painéis de tráfego excluem scrapes e healthchecks. Portas ficam no loopback.

**Diagnóstico sugerido.** Começar pelo alcance do app e status HTTP, depois latência/JVM e saúde do banco. Para auditoria, correlacionar `eventId` em falha de publicação ou topic/partition/offset nos logs do consumidor, consultar lag e verificar `consulta_logs` por SQL. A ausência de um log pode ser atraso, DLT ou perda anterior ao broker; um gráfico HTTP saudável não distingue essas hipóteses.

**Limites.** `up=1` significa scrape acessível. Não representa SLO de negócio nem sucesso Kafka. Não existem métricas próprias de auditoria, alertas, tracing ou exporters PostgreSQL/Kafka. Histogramas e `increase` fornecem aproximações; CPU depende do suporte JVM/ambiente. [Consultas e limitações detalhadas](monitoramento.md).

**Evolução.** Separar contadores de tentativa, aceite do broker, commit efetivo, duplicata, retry e DLT confirmada; evitar contar reentrega como nova persistência. Adicionar alertas de alvo ausente, taxa de erro, latência, lag e DLT com limiares definidos a partir de uma carga de referência.

## 10. Docker, testes e escala horizontal

**Implementado.** Build multi-stage com Wrapper/JDK 21, cache e JRE 21 não root no runtime. O build compila testes, mas não executa a integração isoladamente; a suíte completa é executada no host com PostgreSQL/Kafka reais. `.dockerignore` restringe o contexto e exclui segredos. Healthchecks ordenam a subida, volumes preservam estado e não substituem backups.

Os 207 testes anteriormente aprovados incluem unitários, contratos REST e integração real, com ViaCEP simulado. Schemas/tópicos/grupos são isolados e permanecem após execução; não há Testcontainers ou limpeza automática. Não se deve extrapolar esses testes para uma alegação de benchmark, teste de carga ou resiliência a qualquer desastre.

**Possibilidade arquitetural, não validação de escala.** Múltiplas instâncias podem compartilhar PostgreSQL e o mesmo grupo Kafka; constraints continuam válidas sem locks em memória. Três partições limitam o paralelismo útil a três consumers de partição simultâneos nesse tópico. Instâncias adicionais ainda podem servir HTTP, mas aumentam conexões e pressão no banco. Consumer e API no mesmo processo disputam recursos e sofrem rebalances em deploys.

O Compose atual publica uma porta fixa para o app e não configura balanceador nem múltiplas réplicas. Não basta executar `--scale app` para obter um ambiente balanceado funcional com essa porta. Broker e banco únicos são pontos de falha. Aumentar partições pode mudar a distribuição das chaves; separar deployment do consumer, balancear HTTP e dimensionar pools exigem medição e operação adicionais.

## 11. Evoluções priorizadas

| Prioridade condicionada ao uso | Evolução não implementada |
| --- | --- |
| Antes de exposição pública | Autenticação/autorização, revisão de limites de entrada, TLS/SASL, gestão de segredos e controle de acesso às interfaces |
| Se auditoria não puder perder intenção | Outbox, relay monitorado e política de reprocessamento |
| Operação contínua | Alertas, métricas de auditoria, backup/restauração, capacidade e retenção |
| Mudança funcional | Proveniência e política explícita de atualização manual/expiração |
| Maior carga e disponibilidade | Benchmark, pools, replicação, balanceamento e separação do consumer conforme evidências |

Não foram implementados deploy cloud, CI/CD, Kubernetes ou novas regras nesta etapa documental.
