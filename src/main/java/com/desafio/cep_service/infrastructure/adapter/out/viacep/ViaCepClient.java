package com.desafio.cep_service.infrastructure.adapter.out.viacep;

import com.desafio.cep_service.application.port.out.ConsultaCepPort;
import com.desafio.cep_service.domain.model.Endereco;
import com.desafio.cep_service.infrastructure.adapter.out.viacep.dto.ViaCepResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Optional;

@Component
public class ViaCepClient implements ConsultaCepPort {
    private final RestClient restClient;

    public ViaCepClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public Optional<Endereco> buscar(String cep) {
        ViaCepResponse response = restClient.get()
                .uri("/{cep}/json/", cep)
                .retrieve()
                .body(ViaCepResponse.class);

        if (response == null || Boolean.TRUE.equals(response.erro())) {
            return Optional.empty();
        }
        return Optional.of(new Endereco(response.cep(), response.logradouro(),
                response.complemento(), response.bairro(), response.localidade(), response.uf()));
    }
}
