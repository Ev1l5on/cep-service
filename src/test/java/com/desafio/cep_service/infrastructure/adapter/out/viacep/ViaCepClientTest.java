package com.desafio.cep_service.infrastructure.adapter.out.viacep;

import com.desafio.cep_service.domain.model.Endereco;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class ViaCepClientTest {
    private MockRestServiceServer server;
    private ViaCepClient client;

    @BeforeEach
    void setup() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://viacep.com.br/ws");
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
    void traduzErroDoViaCepParaAusencia() {
        server.expect(requestTo("https://viacep.com.br/ws/00000000/json/"))
                .andRespond(withSuccess("{\"erro\":true}", MediaType.APPLICATION_JSON));
        assertTrue(client.buscar("00000000").isEmpty());
        server.verify();
    }

    @Test
    void preservaTratamentoDeCorpoVazioComoAusencia() {
        server.expect(requestTo("https://viacep.com.br/ws/01001000/json/"))
                .andRespond(withNoContent());
        assertTrue(client.buscar("01001000").isEmpty());
        server.verify();
    }

    @Test
    void propagaIndisponibilidadeDoProvedor() {
        server.expect(requestTo("https://viacep.com.br/ws/01001000/json/"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE));
        assertThrows(HttpServerErrorException.class, () -> client.buscar("01001000"));
        server.verify();
    }
}
