package com.desafio.cep_service.application.usecase;

import com.desafio.cep_service.application.port.out.ConsultaCepPort;
import com.desafio.cep_service.application.port.out.EnderecoRepositoryPort;
import com.desafio.cep_service.application.exception.EnderecoJaCadastradoException;
import com.desafio.cep_service.application.exception.FalhaPersistenciaEnderecoException;
import com.desafio.cep_service.domain.exception.CepNaoEncontradoException;
import com.desafio.cep_service.domain.model.Cep;
import com.desafio.cep_service.domain.model.Endereco;
import com.desafio.cep_service.application.event.ConsultaCepEvento;
import com.desafio.cep_service.application.port.out.PublicarConsultaPort;

public class CepService {
    private final ConsultaCepPort consultaCepPort;
    private final EnderecoRepositoryPort enderecoRepository;
    private final PublicarConsultaPort publicador;

    public CepService(ConsultaCepPort consultaCepPort, EnderecoRepositoryPort enderecoRepository,
                      PublicarConsultaPort publicador) {
        this.consultaCepPort = consultaCepPort;
        this.enderecoRepository = enderecoRepository;
        this.publicador = publicador;
    }

    public Endereco buscar(String cep) {
        Cep cepValidado = new Cep(cep);
        var armazenado = enderecoRepository.buscarPorCep(cepValidado.valor());
        if (armazenado.isPresent()) {
            return concluir(cepValidado.valor(), armazenado.orElseThrow(), ConsultaCepEvento.Source.DATABASE);
        }
        Endereco endereco = consultaCepPort.buscar(cepValidado.valor())
                .orElseThrow(CepNaoEncontradoException::new);
        Endereco persistido;
        ConsultaCepEvento.Source origem = ConsultaCepEvento.Source.VIACEP;
        try {
            persistido = enderecoRepository.salvar(endereco);
        } catch (EnderecoJaCadastradoException ex) {
            // A gravação que falhou já terminou; a recuperação abre outra transação no adaptador.
            persistido = enderecoRepository.buscarPorCep(cepValidado.valor())
                    .orElseThrow(() -> new FalhaPersistenciaEnderecoException(
                            FalhaPersistenciaEnderecoException.Operacao.GRAVACAO,
                            FalhaPersistenciaEnderecoException.Motivo.CONFLITO_NAO_RECUPERADO, ex));
            origem = ConsultaCepEvento.Source.DATABASE;
        }
        return concluir(cepValidado.valor(), persistido, origem);
    }

    private Endereco concluir(String cep, Endereco endereco, ConsultaCepEvento.Source origem) {
        var evento = ConsultaCepEvento.novo(cep, origem, endereco);
        try {
            publicador.publicar(evento);
        } catch (RuntimeException ex) {
            // Proteção da fronteira: falha de auditoria nunca converte sucesso em erro HTTP.
            System.getLogger(CepService.class.getName()).log(System.Logger.Level.ERROR,
                    "Falha ao publicar consulta: eventId={0} tipo={1}",
                    evento.eventId(), ex.getClass().getSimpleName());
        }
        return endereco;
    }
}
