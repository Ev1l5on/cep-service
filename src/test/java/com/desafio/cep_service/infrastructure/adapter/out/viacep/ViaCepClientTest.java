package com.desafio.cep_service.infrastructure.adapter.out.viacep;

import com.desafio.cep_service.application.exception.FalhaConsultaCepException;
import com.desafio.cep_service.domain.model.Endereco;
import com.desafio.cep_service.infrastructure.config.CepConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;
import java.net.SocketTimeoutException;

import static com.desafio.cep_service.application.exception.FalhaConsultaCepException.Motivo.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class ViaCepClientTest {
    private MockRestServiceServer server;
    private ViaCepClient client;

    @BeforeEach
    void setup() {
        RestClient.Builder builder = new CepConfiguration()
                .viaCepRestClient("https://viacep.com.br/ws", 2000, 5000).mutate();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new ViaCepClient(builder.build());
    }

    @Test
    void consultaRotaExternaEConverteDtoParaEndereco() {
        server.expect(requestTo("https://viacep.com.br/ws/01001000/json/"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"cep":"01001-000","logradouro":"Praça da Sé","complemento":"lado ímpar",
                         "bairro":"Sé","localidade":"São Paulo","uf":"SP","ibge":"3550308"}
                        """, MediaType.APPLICATION_JSON));
        assertEquals(new Endereco("01001-000", "Praça da Sé", "lado ímpar", "Sé", "São Paulo", "SP"),
                client.buscar("01001000").orElseThrow());
        server.verify();
    }

    @Test
    void aceitaCepDeLocalidadeSemLogradouroOuBairro() {
        responderJson("{\"cep\":\"01001000\",\"localidade\":\"São Paulo\",\"uf\":\"SP\",\"erro\":false}");
        assertEquals("São Paulo", client.buscar("01001000").orElseThrow().localidade());
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"erro\":true}", "{\"erro\":\"true\"}"})
    void traduzErroDoViaCepParaAusencia(String body) {
        server.expect(requestTo("https://viacep.com.br/ws/00000000/json/"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertTrue(client.buscar("00000000").isEmpty());
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "null", "{", "[]", "42", "{}",
            "{\"cep\":{}}", "{\"erro\":\"sim\"}", "{\"erro\":1}", "{\"erro\":\"\"}",
            "{\"cep\":1001000,\"localidade\":\"São Paulo\",\"uf\":\"SP\"}",
            "{\"cep\":\"01001-000\",\"localidade\":15,\"uf\":\"SP\"}",
            "{\"cep\":\"01001-000\",\"localidade\":\"\",\"uf\":\"SP\"}",
            "{\"cep\":\"01001-000\",\"localidade\":\"São Paulo\"}",
            "{\"cep\":\"01001-000\",\"localidade\":\"São Paulo\",\"uf\":\"ZZ\"}",
            "{\"cep\":\"01002-000\",\"localidade\":\"São Paulo\",\"uf\":\"SP\"}",
            "{\"cep\":\"01001-000\",\"localidade\":\"São Paulo\",\"uf\":\"SP\"} {}"})
    void rejeitaCorpoVazioJsonIncompativelOuDadosInconsistentes(String body) {
        responderJson(body);
        assertEquals(RESPOSTA_INVALIDA,
                assertThrows(FalhaConsultaCepException.class, () -> client.buscar("01001000")).getMotivo());
        server.verify();
    }

    @Test
    void corpoAusenteNaoRepresentaCepInexistente() {
        server.expect(requestTo("https://viacep.com.br/ws/01001000/json/"))
                .andRespond(withNoContent());
        assertEquals(RESPOSTA_INVALIDA,
                assertThrows(FalhaConsultaCepException.class, () -> client.buscar("01001000")).getMotivo());
        server.verify();
    }

    @Test
    void rejeitaContentTypeIncompativel() {
        server.expect(requestTo("https://viacep.com.br/ws/01001000/json/"))
                .andRespond(withSuccess("<html>erro</html>", MediaType.TEXT_HTML));
        assertEquals(RESPOSTA_INVALIDA,
                assertThrows(FalhaConsultaCepException.class, () -> client.buscar("01001000")).getMotivo());
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({"400,RESPOSTA_INVALIDA", "401,RESPOSTA_INVALIDA", "404,RESPOSTA_INVALIDA",
            "408,TIMEOUT", "429,INDISPONIBILIDADE", "500,RESPOSTA_INVALIDA",
            "502,RESPOSTA_INVALIDA", "503,INDISPONIBILIDADE", "504,TIMEOUT"})
    void traduzHttpExternoSemConfundir404ComCepInexistente(int status, String motivo) {
        server.expect(requestTo("https://viacep.com.br/ws/01001000/json/"))
                .andRespond(withStatus(HttpStatusCode.valueOf(status)).body("token=segredo-interno"));
        FalhaConsultaCepException ex = assertThrows(FalhaConsultaCepException.class,
                () -> client.buscar("01001000"));
        assertEquals(FalhaConsultaCepException.Motivo.valueOf(motivo), ex.getMotivo());
        assertFalse(ex.getMessage().contains("segredo-interno"));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"connect timed out", "read timed out"})
    void traduzTimeoutDeConexaoOuLeitura(String message) {
        server.expect(requestTo("https://viacep.com.br/ws/01001000/json/"))
                .andRespond(withException(new SocketTimeoutException(message)));
        assertEquals(TIMEOUT,
                assertThrows(FalhaConsultaCepException.class, () -> client.buscar("01001000")).getMotivo());
        server.verify();
    }

    @Test
    void traduzFalhaDeConexaoParaIndisponibilidade() {
        server.expect(requestTo("https://viacep.com.br/ws/01001000/json/"))
                .andRespond(withException(new ConnectException("Connection refused")));
        assertEquals(INDISPONIBILIDADE,
                assertThrows(FalhaConsultaCepException.class, () -> client.buscar("01001000")).getMotivo());
        server.verify();
    }

    @Test
    void aceitaIndicadorFalseTextualSemAlterarOsDados() {
        responderJson("{\"cep\":\"01001-000\",\"localidade\":\"São Paulo\",\"uf\":\"SP\",\"erro\":\"false\"}");
        assertEquals("01001-000", client.buscar("01001000").orElseThrow().cep());
        server.verify();
    }

    private void responderJson(String body) {
        server.expect(requestTo("https://viacep.com.br/ws/01001000/json/"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }
}
