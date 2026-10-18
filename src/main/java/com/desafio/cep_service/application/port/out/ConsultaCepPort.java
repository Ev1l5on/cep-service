package com.desafio.cep_service.application.port.out;

import com.desafio.cep_service.domain.model.Endereco;

import java.util.Optional;

public interface ConsultaCepPort {
    /** Retorna vazio quando o provedor não encontra o CEP; falhas de integração são propagadas. */
    Optional<Endereco> buscar(String cep);
}
