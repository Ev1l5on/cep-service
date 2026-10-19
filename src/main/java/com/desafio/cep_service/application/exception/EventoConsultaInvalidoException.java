package com.desafio.cep_service.application.exception;

public class EventoConsultaInvalidoException extends IllegalArgumentException {
    public EventoConsultaInvalidoException() {
        super("Evento de consulta inválido ou versão não suportada");
    }
}
