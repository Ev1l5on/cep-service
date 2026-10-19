package com.desafio.cep_service.infrastructure.adapter.in.rest;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.desafio.cep_service.application.usecase.CepService;
import com.desafio.cep_service.application.port.out.PublicarConsultaPort;
import com.desafio.cep_service.application.port.out.EnderecoRepositoryPort;
import com.desafio.cep_service.application.exception.FalhaPersistenciaEnderecoException;
import com.desafio.cep_service.domain.model.Endereco;
import java.util.Optional;
import com.desafio.cep_service.infrastructure.adapter.out.viacep.ViaCepClient;
import com.desafio.cep_service.infrastructure.config.CepConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CepControllerTest {
    private MockMvc mvc;
    private MockRestServiceServer server;
    private EnderecoRepositoryPort repository;

    @BeforeEach
    void setup() {
        RestClient.Builder builder = new CepConfiguration()
                .viaCepRestClient("https://viacep.com.br/ws", 2000, 5000).mutate();
        server = MockRestServiceServer.bindTo(builder).build();
        repository = mock(EnderecoRepositoryPort.class);
        when(repository.salvar(any())).thenAnswer(invocation -> invocation.getArgument(0));
        CepService service = new CepService(new ViaCepClient(builder.build()), repository, mock(PublicarConsultaPort.class));
        mvc = MockMvcBuilders.standaloneSetup(new CepController(service))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void preservaEndpointEContratoJsonDeSucesso() throws Exception {
        responderJson("""
                {"cep":"01001-000","logradouro":"Praça da Sé","complemento":"lado ímpar",
                 "bairro":"Sé","localidade":"São Paulo","uf":"SP"}
                """);
        mvc.perform(get("/api/ceps/01001000"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(content().json("""
                        {"cep":"01001-000","logradouro":"Praça da Sé","complemento":"lado ímpar",
                         "bairro":"Sé","localidade":"São Paulo","uf":"SP","erro":null}
                        """, JsonCompareMode.STRICT));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "01001-000", "1234567", "123456789", "abcdefgh"})
    void cepInvalidoRetorna400SemAcionarViaCep(String cep) throws Exception {
        // Nenhuma expectativa HTTP: o mock falha se o adaptador for acionado.
        validarErro(mvc.perform(get("/api/ceps/{cep}", cep)), 400,
                "Bad Request", "O CEP deve conter exatamente 8 dígitos", "/api/ceps/" + cep);
        verifyNoInteractions(repository);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"erro\":true}", "{\"erro\":\"true\"}"})
    void erroTrueRetorna404(String body) throws Exception {
        responderJson(body);
        validarErro(mvc.perform(get("/api/ceps/01001000")), 404, "Not Found", "CEP não encontrado");
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({"400,502,Bad Gateway", "404,502,Bad Gateway", "500,502,Bad Gateway",
            "503,503,Service Unavailable", "429,503,Service Unavailable",
            "408,504,Gateway Timeout", "504,504,Gateway Timeout"})
    void traduzStatusDoProvedorComMensagemSegura(int externo, int esperado, String error) throws Exception {
        server.expect(requestTo("https://viacep.com.br/ws/01001000/json/"))
                .andRespond(withStatus(HttpStatusCode.valueOf(externo)).body("password=segredo-interno"));
        String message = switch (esperado) {
            case 503 -> "Serviço de CEP temporariamente indisponível";
            case 504 -> "Tempo limite excedido ao consultar o serviço de CEP";
            default -> "Resposta inválida do serviço de CEP";
        };
        validarErro(mvc.perform(get("/api/ceps/01001000")), esperado, error, message);
        server.verify();
    }

    @Test
    void timeoutRetorna504() throws Exception {
        server.expect(requestTo("https://viacep.com.br/ws/01001000/json/"))
                .andRespond(withException(new SocketTimeoutException("read timed out token=segredo-interno")));
        validarErro(mvc.perform(get("/api/ceps/01001000")), 504, "Gateway Timeout",
                "Tempo limite excedido ao consultar o serviço de CEP");
        verify(repository, never()).salvar(any());
        server.verify();
    }

    @Test
    void conexaoRecusadaRetorna503() throws Exception {
        server.expect(requestTo("https://viacep.com.br/ws/01001000/json/"))
                .andRespond(withException(new ConnectException("host interno token=segredo-interno")));
        validarErro(mvc.perform(get("/api/ceps/01001000")), 503, "Service Unavailable",
                "Serviço de CEP temporariamente indisponível");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "[]", "{}", "{\"cep\":{}}", "{\"erro\":\"sim\"}",
            "{\"cep\":\"01001-000\",\"localidade\":\"São Paulo\",\"uf\":\"ZZ\"}"})
    void respostaVaziaOuInvalidaRetorna502(String body) throws Exception {
        responderJson(body);
        validarErro(mvc.perform(get("/api/ceps/01001000")), 502, "Bad Gateway",
                "Resposta inválida do serviço de CEP");
        server.verify();
    }

    @Test
    void falhaInesperadaNaoExpoeDetalhesInternos() throws Exception {
        server.expect(requestTo("https://viacep.com.br/ws/01001000/json/"))
                .andRespond(request -> { throw new IllegalStateException("password=segredo-interno"); });
        validarErro(mvc.perform(get("/api/ceps/01001000")), 500, "Internal Server Error",
                "Erro interno do servidor");
        server.verify();
    }

    @Test
    void metodoIncorretoMantem405ComFormatoPadronizado() throws Exception {
        validarErro(mvc.perform(post("/api/ceps/01001000")), 405, "Method Not Allowed",
                "Não foi possível processar a requisição");
        server.verify();
    }

    @Test
    void registraFalhaUmaVezSemCorpoCredenciaisOuStackTrace() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(ApiExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            server.expect(requestTo("https://viacep.com.br/ws/01001000/json/"))
                    .andRespond(withStatus(HttpStatusCode.valueOf(500)).body("token=segredo-interno"));
            mvc.perform(get("/api/ceps/01001000")).andExpect(status().isBadGateway());
            assertEquals(1, appender.list.size());
            ILoggingEvent event = appender.list.getFirst();
            assertTrue(event.getFormattedMessage().contains("HTTP_500"));
            assertFalse(event.getFormattedMessage().contains("segredo-interno"));
            assertNull(event.getThrowableProxy());
            server.verify();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void enderecoNoBancoMantemContratoSemAcionarClienteExterno() throws Exception {
        when(repository.buscarPorCep("01001000")).thenReturn(Optional.of(
                new Endereco("01001-000", "Praça da Sé", "lado ímpar", "Sé", "São Paulo", "SP")));
        mvc.perform(get("/api/ceps/01001000"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"cep":"01001-000","logradouro":"Praça da Sé","complemento":"lado ímpar",
                         "bairro":"Sé","localidade":"São Paulo","uf":"SP","erro":null}
                        """, JsonCompareMode.STRICT));
        verify(repository, never()).salvar(any());
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({"LEITURA,INDISPONIBILIDADE,503", "GRAVACAO,INDISPONIBILIDADE,503",
            "LEITURA,ERRO,500", "GRAVACAO,ERRO,500", "GRAVACAO,CONFLITO_NAO_RECUPERADO,500"})
    void falhaDoBancoMantemErroSeguroSemFallbackIndevido(String operacao, String motivo, int status)
            throws Exception {
        var op = FalhaPersistenciaEnderecoException.Operacao.valueOf(operacao);
        var reason = FalhaPersistenciaEnderecoException.Motivo.valueOf(motivo);
        var exception = new FalhaPersistenciaEnderecoException(op, reason,
                new RuntimeException("SELECT * FROM enderecos password=segredo-interno"));
        if (op == FalhaPersistenciaEnderecoException.Operacao.LEITURA) {
            when(repository.buscarPorCep("01001000")).thenThrow(exception);
        } else {
            responderJson("{\"cep\":\"01001-000\",\"localidade\":\"São Paulo\",\"uf\":\"SP\"}");
            when(repository.salvar(any())).thenThrow(exception);
        }
        validarErro(mvc.perform(get("/api/ceps/01001000")), status,
                status == 503 ? "Service Unavailable" : "Internal Server Error", exception.getMessage());
        server.verify();
    }

    private void responderJson(String body) {
        server.expect(requestTo("https://viacep.com.br/ws/01001000/json/"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void validarErro(ResultActions result, int status, String error, String message) throws Exception {
        validarErro(result, status, error, message, "/api/ceps/01001000");
    }

    private void validarErro(ResultActions result, int status, String error, String message, String path)
            throws Exception {
        result.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.error").value(error))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.path").value(path))
                .andExpect(response -> {
                    var json = JsonMapper.builder().build().readTree(response.getResponse().getContentAsString());
                    assertEquals(5, json.size());
                    assertDoesNotThrow(() -> Instant.parse(json.get("timestamp").asString()));
                    assertFalse(response.getResponse().getContentAsString().contains("segredo-interno"));
                });
    }
}
