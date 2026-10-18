package com.desafio.cep_service.domain.exception;

public class CepInvalidoException extends IllegalArgumentException {
    public CepInvalidoException() {
        super("O CEP deve conter exatamente 8 dígitos");
    }
}
