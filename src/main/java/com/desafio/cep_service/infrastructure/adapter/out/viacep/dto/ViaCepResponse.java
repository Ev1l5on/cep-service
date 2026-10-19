package com.desafio.cep_service.infrastructure.adapter.out.viacep.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.annotation.JsonDeserialize;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ViaCepResponse(
        String cep,
        String logradouro,
        String complemento,
        String bairro,
        String localidade,
        String uf,
        @JsonDeserialize(using = ViaCepErroDeserializer.class) Boolean erro
) {}
