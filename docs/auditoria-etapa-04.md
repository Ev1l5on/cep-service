# Auditoria tecnica da etapa 04

[README](../README.md) · [Arquitetura atual](arquitetura.md)

Registro historico: os totais de 116/153 testes e o escopo sem Kafka descrevem
a etapa 04. A referencia posterior e de 207 testes; consulte o README para o estado atual.

A auditoria considerou o AGENTS.md e os 15 requisitos informados nesta conversa.
Nao foi encontrada uma copia independente do enunciado original no repositorio.
As alteracoes existentes no workspace foram preservadas.

## Relatorio antes das correcoes

Abreviacoes de testes usadas abaixo:

- **UC:** `CepServiceTest`.
- **REST:** `CepControllerTest`.
- **JPA:** `EnderecoPersistenceAdapterTest`.
- **IT:** `EnderecoPersistenceIntegrationTest`, com PostgreSQL real em schema isolado.
- **ViaCEP:** `ViaCepClientTest` e `CepConfigurationTest`.

| # | Requisito | Situacao inicial | Evidencias de codigo e testes existentes |
| --- | --- | --- | --- |
| 1 | GET consulta primeiro PostgreSQL | IMPLEMENTADO | `CepService.buscar`; UC verifica ordem de leitura, consulta externa e gravacao. |
| 2 | ViaCEP somente na ausencia | IMPLEMENTADO | Retorno antecipado em `CepService`; UC de endereco existente e segunda consulta; IT de reutilizacao. |
| 3 | Persistencia do resultado ViaCEP | IMPLEMENTADO | `EnderecoPersistenceAdapter.salvar`; IT de round-trip e consulta/persistencia. |
| 4 | POST /api/enderecos | NAO IMPLEMENTADO | Nao havia controller, DTO ou caso de uso de cadastro manual. |
| 5 | Prioridade do cadastro manual | PARCIALMENTE IMPLEMENTADO | DB-first priorizava registros locais, mas faltavam cadastro manual e teste POST-GET. |
| 6 | Duplicados retornam 409 | NAO IMPLEMENTADO | Constraint UNIQUE e recuperacao do GET existiam; nao havia cadastro nem handler 409. |
| 7 | Migracoes Flyway | IMPLEMENTADO | V1, starter Flyway e ddl-auto=validate; IT valida migracao e reaplicacao sem execucoes adicionais. |
| 8 | Consultas parametrizadas | IMPLEMENTADO | `EnderecoJpaRepository.findByCep`; IT com entrada contendo SQL sem interpretacao. |
| 9 | Falha PostgreSQL distinta de ausencia | IMPLEMENTADO | Traducao do adaptador; UC sem fallback; JPA distingue leitura/gravacao; REST valida 503/500 seguro. |
| 10 | ViaCEP 502/503/504 | IMPLEMENTADO | Adaptador e advice; REST e ViaCEP verificam status, timeout, transporte e resposta invalida. |
| 11 | Concorrencia sem duplicacao | IMPLEMENTADO | UNIQUE e SQLSTATE/constraint especificos; IT de dois GETs e recuperacao apos rollback. |
| 12 | Sem transacao durante HTTP | IMPLEMENTADO | TransactionTemplate separado da orquestracao; IT verifica ausencia de transacao na chamada externa. |
| 13 | Seis campos Endereco | IMPLEMENTADO | Record inalterado, mapeamentos DTO/JPA e contrato de sucesso com todos os campos. |
| 14 | Contrato e excecoes seguros | IMPLEMENTADO | Advice centralizado; REST valida mensagem, cinco campos de erro e ausencia de detalhes internos. |
| 15 | Testes aprovados | IMPLEMENTADO | Baseline executado antes das alteracoes: 116 testes, zero falhas/erros/ignorados, BUILD SUCCESS. |

## Correcoes incrementais

Foram acrescentados somente o caso de uso `CadastrarEnderecoService`, o
`EnderecoController`, DTOs de entrada/saida separados do dominio e a excecao de
endereco invalido. O cadastro reutiliza `EnderecoRepositoryPort.salvar`, a entidade,
o adaptador PostgreSQL e a constraint ja existentes. A configuracao Spring monta
o novo caso de uso por injecao de construtor.

O advice agora traduz `EnderecoJaCadastradoException` para 409 e
`EnderecoInvalidoException` para 400. A mensagem de duplicidade foi ajustada para
servir tanto a cadastros repetidos quanto concorrentes. Nenhum dado existente e
sobrescrito. A recuperacao de concorrencia do GET permanece intacta.

O POST retorna 201, seis campos e Location apontando para o GET correspondente.
O GET continua retornando seus sete campos, incluindo erro null. O record
Endereco, a migracao, as consultas JPA, o adaptador ViaCEP, suas politicas de erro
e a infraestrutura Docker/PostgreSQL nao foram alterados nesta revisao.

## Testes das lacunas corrigidas

- `CadastrarEnderecoServiceTest`: valida CEP/localidade/UF antes de persistir,
  preserva campos, aceita campos opcionais e propaga duplicidade/falha do banco.
- `EnderecoControllerTest`: 201 com seis campos e Location, 409 seguro, 400 para
  JSON/entradas invalidos e 503/500 para falhas da persistencia.
- Novos cenarios em IT: POST seguido de GET sem ViaCEP; duplicado 409 sem
  sobrescrita; dois POSTs concorrentes com resultados 201/409 e um registro;
  cadastro manual enquanto GET aguarda ViaCEP, com recuperacao do endereco manual;
  dados com apostrofos persistidos literalmente por parametros.

Resultado final: 153 testes aprovados (116 anteriores e 37 novos), zero falhas,
erros ou testes ignorados. Os 12 testes de integracao usam PostgreSQL real e
nenhum teste automatizado depende do ViaCEP publico. O contexto Spring iniciou,
Flyway confirmou V1 e Hibernate validou o esquema. `git diff --check` passou.

Os 15 requisitos estao IMPLEMENTADOS apos as correcoes. A etapa 04 esta pronta
para encerramento no escopo auditado. A verificacao integral de um enunciado
adicional dependeria de seu conteudo, que nao estava disponivel no projeto.

Nenhum commit, push, deploy, Kafka, exclusao de dados ou remocao de volumes foi
executado. Os schemas isolados de testes permanecem no PostgreSQL, seguindo a
politica existente de nao apagar dados.
