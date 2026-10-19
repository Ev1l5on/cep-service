package com.desafio.cep_service.application.port.out;

import com.desafio.cep_service.domain.model.Endereco;

import java.util.Optional;

public interface EnderecoRepositoryPort {
    /** Vazio significa ausência confirmada; falhas de acesso devem gerar exceção. */
    Optional<Endereco> buscarPorCep(String cep);

    /** Retorna somente após confirmação da gravação; conflitos são sinalizados explicitamente. */
    Endereco salvar(Endereco endereco);
}
