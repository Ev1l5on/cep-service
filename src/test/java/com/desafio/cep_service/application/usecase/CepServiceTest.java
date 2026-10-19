package com.desafio.cep_service.application.usecase;

import com.desafio.cep_service.application.port.out.ConsultaCepPort;
import com.desafio.cep_service.application.port.out.EnderecoRepositoryPort;
import com.desafio.cep_service.application.exception.EnderecoJaCadastradoException;
import com.desafio.cep_service.application.exception.FalhaPersistenciaEnderecoException;
import org.junit.jupiter.api.BeforeEach;
import com.desafio.cep_service.domain.exception.CepInvalidoException;
import com.desafio.cep_service.domain.exception.CepNaoEncontradoException;
import com.desafio.cep_service.domain.model.Endereco;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CepServiceTest {
    private final ConsultaCepPort port = mock(ConsultaCepPort.class);
    private final EnderecoRepositoryPort repository = mock(EnderecoRepositoryPort.class);
    private final CepService service = new CepService(port, repository);

    @BeforeEach
    void setup() {
        when(repository.salvar(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void retornaEnderecoEDelegaUmaVezParaPortaExterna() {
        Endereco endereco = new Endereco("01001-000", "Praça da Sé", "lado ímpar", "Sé", "São Paulo", "SP");
        when(port.buscar("01001000")).thenReturn(Optional.of(endereco));

        assertSame(endereco, service.buscar("01001000"));
        verify(port).buscar("01001000");
        verifyNoMoreInteractions(port);
        var order = inOrder(repository, port);
        order.verify(repository).buscarPorCep("01001000");
        order.verify(port).buscar("01001000");
        order.verify(repository).salvar(endereco);
    }

    @Test
    void naoConsultaPortaQuandoCepInvalido() {
        assertThrows(CepInvalidoException.class, () -> service.buscar("01001-000"));
        verifyNoInteractions(port, repository);
    }

    @Test
    void preservaMensagemQuandoCepNaoEncontrado() {
        when(port.buscar("00000000")).thenReturn(Optional.empty());
        assertEquals("CEP não encontrado",
                assertThrows(CepNaoEncontradoException.class, () -> service.buscar("00000000")).getMessage());
    }

    @Test
    void propagaFalhaDaIntegracaoSemConfundirComAusencia() {
        RuntimeException falha = new RuntimeException("Falha de integração");
        when(port.buscar("01001000")).thenThrow(falha);
        assertSame(falha, assertThrows(RuntimeException.class, () -> service.buscar("01001000")));
    }

    @Test
    void enderecoExistenteNaoAcionaViaCepNemGravacao() {
        Endereco endereco = endereco();
        when(repository.buscarPorCep("01001000")).thenReturn(Optional.of(endereco));
        assertSame(endereco, service.buscar("01001000"));
        verifyNoInteractions(port);
        verify(repository, never()).salvar(any());
    }

    @Test
    void segundaConsultaReutilizaEnderecoPersistido() {
        Endereco endereco = endereco();
        when(repository.buscarPorCep("01001000")).thenReturn(Optional.empty(), Optional.of(endereco));
        when(port.buscar("01001000")).thenReturn(Optional.of(endereco));
        assertSame(endereco, service.buscar("01001000"));
        assertSame(endereco, service.buscar("01001000"));
        verify(port, times(1)).buscar("01001000");
        verify(repository, times(1)).salvar(endereco);
    }

    @Test
    void falhaDeLeituraNaoFazFallbackExterno() {
        var falha = falha(FalhaPersistenciaEnderecoException.Operacao.LEITURA);
        when(repository.buscarPorCep("01001000")).thenThrow(falha);
        assertSame(falha, assertThrows(FalhaPersistenciaEnderecoException.class,
                () -> service.buscar("01001000")));
        verifyNoInteractions(port);
        verify(repository, never()).salvar(any());
    }

    @Test
    void falhaDeGravacaoNaoRetornaEnderecoNaoPersistido() {
        when(port.buscar("01001000")).thenReturn(Optional.of(endereco()));
        var falha = falha(FalhaPersistenciaEnderecoException.Operacao.GRAVACAO);
        when(repository.salvar(any())).thenThrow(falha);
        assertSame(falha, assertThrows(FalhaPersistenciaEnderecoException.class,
                () -> service.buscar("01001000")));
    }

    @Test
    void conflitoRecuperaRegistroVencedorEmNovaConsulta() {
        Endereco vencedor = endereco();
        when(repository.buscarPorCep("01001000")).thenReturn(Optional.empty(), Optional.of(vencedor));
        when(port.buscar("01001000")).thenReturn(Optional.of(vencedor));
        when(repository.salvar(any())).thenThrow(new EnderecoJaCadastradoException(null));
        assertSame(vencedor, service.buscar("01001000"));
        verify(repository, times(2)).buscarPorCep("01001000");
        verify(port, times(1)).buscar("01001000");
    }

    @Test
    void conflitoSemRegistroRecuperavelNaoRetornaSucesso() {
        when(port.buscar("01001000")).thenReturn(Optional.of(endereco()));
        when(repository.salvar(any())).thenThrow(new EnderecoJaCadastradoException(null));
        var exception = assertThrows(FalhaPersistenciaEnderecoException.class,
                () -> service.buscar("01001000"));
        assertEquals(FalhaPersistenciaEnderecoException.Motivo.CONFLITO_NAO_RECUPERADO, exception.getMotivo());
    }

    @Test
    void falhaNaReleituraAposConflitoNaoRetornaEnderecoExterno() {
        when(repository.buscarPorCep("01001000")).thenReturn(Optional.empty())
                .thenThrow(falha(FalhaPersistenciaEnderecoException.Operacao.LEITURA));
        when(port.buscar("01001000")).thenReturn(Optional.of(endereco()));
        when(repository.salvar(any())).thenThrow(new EnderecoJaCadastradoException(null));
        assertThrows(FalhaPersistenciaEnderecoException.class, () -> service.buscar("01001000"));
    }

    @Test
    void ausenciaExternaNaoPersisteEndereco() {
        when(port.buscar("01001000")).thenReturn(Optional.empty());
        assertThrows(CepNaoEncontradoException.class, () -> service.buscar("01001000"));
        verify(repository, never()).salvar(any());
    }

    private Endereco endereco() {
        return new Endereco("01001-000", "Praça da Sé", "lado ímpar", "Sé", "São Paulo", "SP");
    }

    private FalhaPersistenciaEnderecoException falha(FalhaPersistenciaEnderecoException.Operacao operacao) {
        return new FalhaPersistenciaEnderecoException(operacao,
                FalhaPersistenciaEnderecoException.Motivo.INDISPONIBILIDADE,
                new RuntimeException("SQL password=segredo-interno"));
    }
}
