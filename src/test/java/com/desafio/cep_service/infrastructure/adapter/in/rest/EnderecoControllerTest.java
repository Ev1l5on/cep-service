package com.desafio.cep_service.infrastructure.adapter.in.rest;

import com.desafio.cep_service.application.exception.EnderecoJaCadastradoException;
import com.desafio.cep_service.application.exception.FalhaPersistenciaEnderecoException;
import com.desafio.cep_service.application.port.out.EnderecoRepositoryPort;
import com.desafio.cep_service.application.usecase.CadastrarEnderecoService;
import com.desafio.cep_service.domain.model.Endereco;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class EnderecoControllerTest {
    private static final String REQUEST = """
            {"cep":"12345678","logradouro":"Rua manual","complemento":"Casa 1",
             "bairro":"Centro","localidade":"São Paulo","uf":"SP"}
            """;
    private final EnderecoRepositoryPort repository = mock(EnderecoRepositoryPort.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new EnderecoController(new CadastrarEnderecoService(repository)))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void cadastroRetorna201LocationEExatamenteSeisCampos() throws Exception {
        when(repository.salvar(any())).thenReturn(new Endereco(
                "12345-678", "Rua manual", "Casa 1", "Centro", "São Paulo", "SP"));
        mvc.perform(post("/api/enderecos").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/ceps/12345678"))
                .andExpect(content().json(REQUEST.replace("12345678", "12345-678"), JsonCompareMode.STRICT));
        verify(repository).salvar(new Endereco("12345678", "Rua manual", "Casa 1", "Centro", "São Paulo", "SP"));
    }

    @Test
    void duplicadoRetorna409SemDetalhesSql() throws Exception {
        when(repository.salvar(any())).thenThrow(new EnderecoJaCadastradoException(
                new RuntimeException("INSERT INTO enderecos password=segredo-interno")));
        var result = mvc.perform(post("/api/enderecos").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Já existe um endereço cadastrado para o CEP informado"))
                .andReturn();
        validarErroSeguro(result.getResponse().getContentAsString(), 409);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[]", "{", "{\"cep\":{}}",
            "{\"cep\":\"12345-678\",\"localidade\":\"São Paulo\",\"uf\":\"SP\"}",
            "{\"cep\":\"12345678\",\"localidade\":\"\",\"uf\":\"SP\"}",
            "{\"cep\":\"12345678\",\"localidade\":\"São Paulo\",\"uf\":\"ZZ\"}",
            "{\"cep\":\"' OR '1'='1\",\"localidade\":\"São Paulo\",\"uf\":\"SP\"}"})
    void entradasInvalidasRetornam400SemAcessoAoBanco(String body) throws Exception {
        var result = mvc.perform(post("/api/enderecos").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andReturn();
        validarErroSeguro(result.getResponse().getContentAsString(), 400);
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"INDISPONIBILIDADE", "ERRO"})
    void falhaDeGravacaoRetorna503Ou500SemDadosInternos(String motivo) throws Exception {
        int status = "INDISPONIBILIDADE".equals(motivo) ? 503 : 500;
        when(repository.salvar(any())).thenThrow(new FalhaPersistenciaEnderecoException(
                FalhaPersistenciaEnderecoException.Operacao.GRAVACAO,
                FalhaPersistenciaEnderecoException.Motivo.valueOf(motivo),
                new RuntimeException("SQL password=segredo-interno")));
        var result = mvc.perform(post("/api/enderecos").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().is(status)).andReturn();
        validarErroSeguro(result.getResponse().getContentAsString(), status);
    }

    private void validarErroSeguro(String body, int status) {
        var error = JsonMapper.builder().build().readTree(body);
        assertEquals(5, error.size());
        assertEquals(status, error.get("status").asInt());
        assertEquals("/api/enderecos", error.get("path").asString());
        assertNotNull(error.get("error").asString());
        assertDoesNotThrow(() -> Instant.parse(error.get("timestamp").asString()));
        assertFalse(body.contains("segredo-interno"));
        assertFalse(body.contains("INSERT"));
        assertFalse(body.contains("stackTrace"));
    }
}
