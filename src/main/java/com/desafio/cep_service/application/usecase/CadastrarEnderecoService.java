package com.desafio.cep_service.application.usecase;

import com.desafio.cep_service.application.port.out.EnderecoRepositoryPort;
import com.desafio.cep_service.domain.exception.EnderecoInvalidoException;
import com.desafio.cep_service.domain.model.Cep;
import com.desafio.cep_service.domain.model.Endereco;

import java.util.Set;

public class CadastrarEnderecoService {
    private static final Set<String> UFS = Set.of("AC", "AL", "AP", "AM", "BA", "CE", "DF",
            "ES", "GO", "MA", "MT", "MS", "MG", "PA", "PB", "PR", "PE", "PI", "RJ",
            "RN", "RS", "RO", "RR", "SC", "SP", "SE", "TO");
    private final EnderecoRepositoryPort repository;

    public CadastrarEnderecoService(EnderecoRepositoryPort repository) {
        this.repository = repository;
    }

    public Endereco cadastrar(Endereco endereco) {
        if (endereco == null) {
            throw new EnderecoInvalidoException();
        }
        new Cep(endereco.cep());
        if (endereco.localidade() == null || endereco.localidade().isBlank()
                || endereco.localidade().length() > 255
                || endereco.uf() == null || !UFS.contains(endereco.uf())) {
            throw new EnderecoInvalidoException();
        }
        // A constraint UNIQUE é a autoridade para duplicidade, inclusive sob concorrência.
        return repository.salvar(endereco);
    }
}
