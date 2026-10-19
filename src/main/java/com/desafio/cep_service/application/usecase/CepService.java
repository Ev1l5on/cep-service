package com.desafio.cep_service.application.usecase;

import com.desafio.cep_service.application.port.out.ConsultaCepPort;
import com.desafio.cep_service.application.port.out.EnderecoRepositoryPort;
import com.desafio.cep_service.application.exception.EnderecoJaCadastradoException;
import com.desafio.cep_service.application.exception.FalhaPersistenciaEnderecoException;
import com.desafio.cep_service.domain.exception.CepNaoEncontradoException;
import com.desafio.cep_service.domain.model.Cep;
import com.desafio.cep_service.domain.model.Endereco;

public class CepService {
    private final ConsultaCepPort consultaCepPort;
    private final EnderecoRepositoryPort enderecoRepository;

    public CepService(ConsultaCepPort consultaCepPort, EnderecoRepositoryPort enderecoRepository) {
        this.consultaCepPort = consultaCepPort;
        this.enderecoRepository = enderecoRepository;
    }

    public Endereco buscar(String cep) {
        Cep cepValidado = new Cep(cep);
        var armazenado = enderecoRepository.buscarPorCep(cepValidado.valor());
        if (armazenado.isPresent()) {
            return armazenado.get();
        }
        Endereco endereco = consultaCepPort.buscar(cepValidado.valor())
                .orElseThrow(CepNaoEncontradoException::new);
        try {
            return enderecoRepository.salvar(endereco);
        } catch (EnderecoJaCadastradoException ex) {
            // A gravação que falhou já terminou; a recuperação abre outra transação no adaptador.
            return enderecoRepository.buscarPorCep(cepValidado.valor())
                    .orElseThrow(() -> new FalhaPersistenciaEnderecoException(
                            FalhaPersistenciaEnderecoException.Operacao.GRAVACAO,
                            FalhaPersistenciaEnderecoException.Motivo.CONFLITO_NAO_RECUPERADO, ex));
        }
    }
}
