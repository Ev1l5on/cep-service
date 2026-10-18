package com.desafio.cep_service.infrastructure.adapter.in.rest.dto;

import com.desafio.cep_service.domain.model.Endereco;

public record CepResponse(
        String cep,
        String logradouro,
        String complemento,
        String bairro,
        String localidade,
        String uf,
        Boolean erro
) {
    public static CepResponse from(Endereco endereco) {
        // O ViaCEP omite erro no sucesso; preservamos o campo público com valor null.
        return new CepResponse(endereco.cep(), endereco.logradouro(), endereco.complemento(),
                endereco.bairro(), endereco.localidade(), endereco.uf(), null);
    }
}
