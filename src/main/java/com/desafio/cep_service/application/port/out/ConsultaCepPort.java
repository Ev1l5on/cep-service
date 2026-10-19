package com.desafio.cep_service.application.port.out;

import com.desafio.cep_service.domain.model.Endereco;

import java.util.Optional;

public interface ConsultaCepPort {
    /**
     * Retorna vazio somente quando o provedor informa que o CEP não existe.
     * @throws com.desafio.cep_service.application.exception.FalhaConsultaCepException
     *         em falhas de transporte ou respostas externas inválidas
     */
    Optional<Endereco> buscar(String cep);
}
