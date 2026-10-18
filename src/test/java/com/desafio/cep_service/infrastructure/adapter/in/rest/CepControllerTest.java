package com.desafio.cep_service.infrastructure.adapter.in.rest;

import com.desafio.cep_service.application.port.out.ConsultaCepPort;
import com.desafio.cep_service.application.usecase.CepService;
import com.desafio.cep_service.domain.model.Endereco;
import org.junit.jupiter.api.Test;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CepControllerTest {
    @Test
    void preservaEndpointEContratoJsonDeSucesso() throws Exception {
        ConsultaCepPort port = mock(ConsultaCepPort.class);
        when(port.buscar("01001000")).thenReturn(Optional.of(
                new Endereco("01001-000", "Praça da Sé", "lado ímpar", "Sé", "São Paulo", "SP")));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new CepController(new CepService(port))).build();

        mvc.perform(get("/api/ceps/01001000"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(content().json("""
                        {"cep":"01001-000","logradouro":"Praça da Sé","complemento":"lado ímpar",
                         "bairro":"Sé","localidade":"São Paulo","uf":"SP","erro":null}
                        """, JsonCompareMode.STRICT));
        verify(port).buscar("01001000");
    }
}
