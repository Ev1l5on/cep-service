package com.desafio.cep_service.application.usecase;

import com.desafio.cep_service.application.port.out.ConsultaCepPort;
import com.desafio.cep_service.domain.exception.CepNaoEncontradoException;
import com.desafio.cep_service.domain.model.Cep;
import com.desafio.cep_service.domain.model.Endereco;

public class CepService {
    private final ConsultaCepPort consultaCepPort;

    public CepService(ConsultaCepPort consultaCepPort) {
        this.consultaCepPort = consultaCepPort;
    }

    public Endereco buscar(String cep) {
        Cep cepValidado = new Cep(cep);
        return consultaCepPort.buscar(cepValidado.valor())
                .orElseThrow(CepNaoEncontradoException::new);
    }
}
