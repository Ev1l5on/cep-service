package com.desafio.cep_service.infrastructure.adapter.in.messaging;

/** Exceção sanitizada: não carrega SQL, credenciais ou mensagens do driver nos logs Kafka. */
public class FalhaProcessamentoAuditoriaException extends RuntimeException {
    public FalhaProcessamentoAuditoriaException(String tipo) {
        super("Falha ao persistir auditoria: " + tipo);
    }
}
