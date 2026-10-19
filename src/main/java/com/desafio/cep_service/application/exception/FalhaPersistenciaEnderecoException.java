package com.desafio.cep_service.application.exception;

public class FalhaPersistenciaEnderecoException extends RuntimeException {
    public enum Operacao { LEITURA, GRAVACAO }
    public enum Motivo { INDISPONIBILIDADE, ERRO, CONFLITO_NAO_RECUPERADO }

    private final Operacao operacao;
    private final Motivo motivo;

    public FalhaPersistenciaEnderecoException(Operacao operacao, Motivo motivo, Throwable causa) {
        super(motivo == Motivo.INDISPONIBILIDADE
                ? "Armazenamento de endereços temporariamente indisponível"
                : "Não foi possível concluir a operação de armazenamento do endereço", causa);
        this.operacao = operacao;
        this.motivo = motivo;
    }

    public Operacao getOperacao() { return operacao; }
    public Motivo getMotivo() { return motivo; }
}
