package com.desafio.cep_service.application.exception;

public class FalhaConsultaCepException extends RuntimeException {
    public enum Motivo {
        TIMEOUT, INDISPONIBILIDADE, RESPOSTA_INVALIDA
    }

    private final Motivo motivo;
    private final String diagnostico;

    public FalhaConsultaCepException(Motivo motivo, String diagnostico, Throwable causa) {
        super(switch (motivo) {
            case TIMEOUT -> "Tempo limite excedido ao consultar o serviço de CEP";
            case INDISPONIBILIDADE -> "Serviço de CEP temporariamente indisponível";
            case RESPOSTA_INVALIDA -> "Resposta inválida do serviço de CEP";
        }, causa);
        this.motivo = motivo;
        this.diagnostico = diagnostico;
    }

    public Motivo getMotivo() {
        return motivo;
    }

    public String getDiagnostico() {
        return diagnostico;
    }
}
