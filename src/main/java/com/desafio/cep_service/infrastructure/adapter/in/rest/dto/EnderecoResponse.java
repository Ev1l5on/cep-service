package com.desafio.cep_service.infrastructure.adapter.in.rest.dto;

import com.desafio.cep_service.domain.model.Endereco;

public record EnderecoResponse(
        String cep,
        String logradouro,
        String complemento,
        String bairro,
        String localidade,
        String uf
) {
    public static EnderecoResponse from(Endereco endereco) {
        return new EnderecoResponse(endereco.cep(), endereco.logradouro(), endereco.complemento(),
                endereco.bairro(), endereco.localidade(), endereco.uf());
    }
}
