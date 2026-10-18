
package com.desafio.cep_service.service;

import com.desafio.cep_service.application.port.out.ConsultaCepPort;
import com.desafio.cep_service.dto.CepResponse;
import org.springframework.stereotype.Service;

@Service
public class CepService {

    private final ConsultaCepPort consultaCepPort;

    public CepService(ConsultaCepPort consultaCepPort) {
        this.consultaCepPort = consultaCepPort;
    }

    public CepResponse buscar(String cep) {

        if (cep == null || !cep.matches("\\d{8}")) {
            throw new IllegalArgumentException(
                    "O CEP deve conter exatamente 8 dígitos"
            );
        }

        CepResponse response = consultaCepPort.buscar(cep);

        if (response == null || Boolean.TRUE.equals(response.erro())) {
            throw new IllegalArgumentException(
                    "CEP não encontrado"
            );
        }

        return response;
    }
}
