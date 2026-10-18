package com.desafio.cep_service.domain.model;

import com.desafio.cep_service.domain.exception.CepInvalidoException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class CepTest {
    @Test
    void aceitaOitoDigitosPreservandoZerosAEsquerda() {
        assertEquals("01001000", new Cep("01001000").valor());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "1234567", "123456789", "01001-000", "abcdefgh", " 01001000", "01001000 ", "1234567\n"})
    void rejeitaValoresForaDoContrato(String valor) {
        assertEquals("O CEP deve conter exatamente 8 dígitos",
                assertThrows(CepInvalidoException.class, () -> new Cep(valor)).getMessage());
    }
}
