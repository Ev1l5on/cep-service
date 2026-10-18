package com.desafio.cep_service.domain.model;

import com.desafio.cep_service.domain.exception.CepInvalidoException;

public record Cep(String valor) {
    public Cep {
        if (valor == null || !valor.matches("\\d{8}")) {
            throw new CepInvalidoException();
        }
    }
}
