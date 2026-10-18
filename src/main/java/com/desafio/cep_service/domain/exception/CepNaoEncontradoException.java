package com.desafio.cep_service.domain.exception;

public class CepNaoEncontradoException extends IllegalArgumentException {
    public CepNaoEncontradoException() {
        super("CEP não encontrado");
    }
}
