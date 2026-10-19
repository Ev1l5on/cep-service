package com.desafio.cep_service.domain.exception;

public class EnderecoInvalidoException extends IllegalArgumentException {
    public EnderecoInvalidoException() {
        super("Informe uma localidade de até 255 caracteres e uma UF válida");
    }
}
