# Instruções do projeto CEP Service

## Tecnologias
- Java 21
- Spring Boot 4.1.1
- Maven
- PostgreSQL
- Spring Data JPA
- Apache Kafka
- Docker e Docker Compose
- Spring Boot Actuator e Prometheus

## Arquitetura
- Utilizar arquitetura hexagonal (Ports and Adapters).
- Aplicar os princípios SOLID, especialmente SRP, DIP e ISP.
- Separar o projeto em `domain`, `application` e `infrastructure`.
- Utilizar ports and adapters para desacoplar regras de negócio das tecnologias externas.
- Manter o domínio independente de Spring, JPA, Kafka e clientes HTTP.
- Preservar o pacote base `com.desafio.cep_service`.
- Priorizar soluções simples e justificáveis tecnicamente.

## Design Patterns
- **Adapter:** encapsular integrações com ViaCEP, PostgreSQL e Kafka.
- **Repository:** abstrair operações de persistência por interfaces.
- **Dependency Injection:** utilizar injeção de dependências por construtor.
- **DTO:** separar contratos HTTP, respostas externas e modelos de domínio.
- **Producer–Consumer:** utilizar Kafka para desacoplar a publicação e o processamento de logs.
- **Exception Handler:** centralizar o tratamento de erros HTTP.
- Não implementar Strategy, Factory, CQRS ou Event Sourcing sem necessidade comprovada.

## Organização dos pacotes

### Domain
- `domain.model`: modelos e regras de domínio.
- `domain.exception`: exceções específicas do domínio.

### Application
- `application.usecase`: implementação dos casos de uso.
- `application.port.out`: interfaces para serviços externos, persistência e publicação de eventos.

### Infrastructure
- `infrastructure.adapter.in.rest`: controllers REST.
- `infrastructure.adapter.in.messaging`: consumidores Kafka.
- `infrastructure.adapter.out.viacep`: integração com ViaCEP.
- `infrastructure.adapter.out.persistence`: implementação da persistência PostgreSQL.
- `infrastructure.adapter.out.messaging`: produtores Kafka.
- `infrastructure.config`: configurações técnicas.

## Regras de negócio
- Validar o CEP informado, aceitando exatamente oito dígitos.
- Consultar primeiro o PostgreSQL.
- Consultar ViaCEP quando o endereço não estiver cadastrado.
- Retornar os dados do endereço de maneira padronizada.
- Tratar adequadamente CEP inexistente e indisponibilidade do serviço externo.
- Publicar eventos de consulta no Kafka sem aguardar a persistência dos logs para responder à requisição HTTP.
- Registrar CEP, data e hora, origem da consulta, resultado e informações relevantes da resposta da API.
- Persistir logs de forma assíncrona por meio de um consumidor Kafka.
- Definir e documentar o comportamento quando a publicação do evento falhar.

## Segurança
- Validar todas as entradas recebidas pela API.
- Prevenir SQL Injection utilizando consultas parametrizadas e métodos seguros do Spring Data JPA.
- Não expor stack traces, credenciais ou informações internas nas respostas HTTP.
- Não armazenar senhas, tokens ou chaves diretamente no código.
- Utilizar variáveis de ambiente para configurações sensíveis.

## Qualidade
- Criar testes automatizados para regras de negócio.
- Testar cenários de sucesso, CEP inválido, CEP inexistente e falha de integração.
- Tratar exceções adequadamente.
- Manter código limpo, legível e organizado.
- Evitar duplicação de código e abstrações desnecessárias.
- Utilizar nomes descritivos para classes, métodos e variáveis.
- Priorizar refatorações incrementais, preservando funcionalidades existentes.
- Executar `./mvnw clean test` após alterações relevantes, quando o ambiente permitir.

## Observabilidade
- Utilizar Spring Boot Actuator para endpoints de saúde e métricas.
- Expor métricas compatíveis com Prometheus.
- Monitorar latência, quantidade de requisições e taxas de erro.
- Utilizar logs estruturados sem informações sensíveis.
- Documentar como visualizar as métricas e os dashboards.

## Docker e Cloud
- Preparar a aplicação para execução com Docker.
- Utilizar Docker Compose para configurar os serviços necessários.
- Evitar credenciais fixas nos arquivos de configuração.
- Preparar o projeto para implantação em AWS ou Azure.
- Documentar variáveis de ambiente e instruções de execução.

## Git
- Não executar commits ou push automaticamente.
- Não alterar arquivos fora do escopo solicitado.
- Preservar o histórico Git existente.
- Manter alterações pequenas e relacionadas a uma funcionalidade.
- Sugerir mensagens de commit seguindo Conventional Commits.
- Não versionar arquivos gerados, credenciais ou informações sensíveis.

## Instruções para o Codex
- Antes de modificar arquivos, analisar a implementação existente.
- Respeitar as instruções deste documento.
- Implementar somente o escopo solicitado no prompt atual.
- Não reescrever funcionalidades que já funcionam sem justificativa técnica.
- Explicar as decisões arquiteturais adotadas.
- Informar quais arquivos foram criados, modificados ou removidos.
- Executar os testes disponíveis e informar resultados ou limitações.
- Não executar comandos destrutivos sem autorização.
- Priorizar a entrega funcional do desafio técnico antes de melhorias opcionais.