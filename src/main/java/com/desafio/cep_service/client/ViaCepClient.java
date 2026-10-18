package com.desafio.cep_service.client;

import com.desafio.cep_service.application.port.out.ConsultaCepPort;
import com.desafio.cep_service.dto.CepResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ViaCepClient implements ConsultaCepPort {

    private final RestClient restClient;

    public ViaCepClient() {
        this.restClient = RestClient.builder()
                .baseUrl("https://viacep.com.br/ws")
                .build();
    }

    @Override
    public CepResponse buscar(String cep) {
        return restClient.get()
                .uri("/{cep}/json/", cep)
                .retrieve()
                .body(CepResponse.class);
    }
}