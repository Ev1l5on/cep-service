package com.desafio.cep_service.application.exception;

public class EnderecoJaCadastradoException extends RuntimeException {
    public EnderecoJaCadastradoException(Throwable causa) {
        super("Já existe um endereço cadastrado para o CEP informado", causa);
    }
}
