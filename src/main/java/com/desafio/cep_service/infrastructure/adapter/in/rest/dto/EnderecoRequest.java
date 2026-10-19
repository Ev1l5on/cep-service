package com.desafio.cep_service.infrastructure.adapter.in.rest.dto;

import com.desafio.cep_service.domain.model.Endereco;

public record EnderecoRequest(
        String cep,
        String logradouro,
        String complemento,
        String bairro,
        String localidade,
        String uf
) {
    public Endereco toDomain() {
        return new Endereco(cep, logradouro, complemento, bairro, localidade, uf);
    }
}
