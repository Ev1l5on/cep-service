package com.desafio.cep_service.application.usecase;

import com.desafio.cep_service.application.event.ConsultaCepEvento;
import com.desafio.cep_service.application.exception.EnderecoJaCadastradoException;
import com.desafio.cep_service.application.exception.FalhaConsultaCepException;
import com.desafio.cep_service.application.port.out.ConsultaCepPort;
import com.desafio.cep_service.application.port.out.EnderecoRepositoryPort;
import com.desafio.cep_service.application.port.out.PublicarConsultaPort;
import com.desafio.cep_service.domain.model.Endereco;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditoriaConsultaServiceTest {
    private final ConsultaCepPort externo = mock(ConsultaCepPort.class);
    private final EnderecoRepositoryPort repository = mock(EnderecoRepositoryPort.class);
    private final PublicarConsultaPort publisher = mock(PublicarConsultaPort.class);
    private final CepService service = new CepService(externo, repository, publisher);
    private final Endereco endereco = new Endereco("01001-000", "Praça da Sé", "", "Sé", "São Paulo", "SP");

    @Test
    void databasePublicaEventoVersionadoComHorarioEEndereco() {
        when(repository.buscarPorCep("01001000")).thenReturn(Optional.of(endereco));
        Instant before = Instant.now();
        assertSame(endereco, service.buscar("01001000"));
        var captor = ArgumentCaptor.forClass(ConsultaCepEvento.class);
        verify(publisher).publicar(captor.capture());
        var event = captor.getValue();
        assertNotNull(event.eventId());
        assertEquals(1, event.eventVersion());
        assertEquals("01001000", event.cep());
        assertEquals(ConsultaCepEvento.Source.DATABASE, event.source());
        assertEquals(endereco, event.responseData());
        assertFalse(event.consultedAt().isBefore(before));
        assertFalse(event.consultedAt().isAfter(Instant.now()));
        verifyNoInteractions(externo);
    }

    @Test
    void viaCepPublicaSomenteDepoisDaGravacaoComOrigemViaCep() {
        when(externo.buscar("01001000")).thenReturn(Optional.of(endereco));
        when(repository.salvar(endereco)).thenReturn(endereco);
        service.buscar("01001000");
        var order = inOrder(repository, externo, publisher);
        order.verify(repository).buscarPorCep("01001000");
        order.verify(externo).buscar("01001000");
        order.verify(repository).salvar(endereco);
        order.verify(publisher).publicar(argThat(e -> e.source() == ConsultaCepEvento.Source.VIACEP));
    }

    @Test
    void recuperacaoConcorrentePublicaOrigemDoRegistroRetornado() {
        when(repository.buscarPorCep("01001000")).thenReturn(Optional.empty(), Optional.of(endereco));
        when(externo.buscar("01001000")).thenReturn(Optional.of(endereco));
        when(repository.salvar(endereco)).thenThrow(new EnderecoJaCadastradoException(null));
        assertSame(endereco, service.buscar("01001000"));
        verify(publisher).publicar(argThat(e -> e.source() == ConsultaCepEvento.Source.DATABASE));
    }

    @Test
    void cadaGetTemEventIdNovo() {
        when(repository.buscarPorCep("01001000")).thenReturn(Optional.of(endereco));
        service.buscar("01001000");
        service.buscar("01001000");
        var captor = ArgumentCaptor.forClass(ConsultaCepEvento.class);
        verify(publisher, times(2)).publicar(captor.capture());
        assertNotEquals(captor.getAllValues().get(0).eventId(), captor.getAllValues().get(1).eventId());
    }

    @Test
    void falhaImediataNaPortaNaoAlteraRetornoBemSucedido() {
        when(repository.buscarPorCep("01001000")).thenReturn(Optional.of(endereco));
        doThrow(new IllegalStateException("token=segredo-interno")).when(publisher).publicar(any());
        assertSame(endereco, service.buscar("01001000"));
    }

    @Test
    void cepInvalidoNaoPublica() {
        assertThrows(IllegalArgumentException.class, () -> service.buscar("invalid"));
        verifyNoInteractions(repository, externo, publisher);
    }

    @Test
    void falhaExternaNaoPublica() {
        when(externo.buscar("01001000")).thenThrow(new FalhaConsultaCepException(
                FalhaConsultaCepException.Motivo.TIMEOUT, "TIMEOUT", null));
        assertThrows(FalhaConsultaCepException.class, () -> service.buscar("01001000"));
        verifyNoInteractions(publisher);
    }

    @Test
    void postNaoPublicaEventoDeConsulta() {
        Endereco entrada = new Endereco("01001000", "Praça da Sé", "", "Sé", "São Paulo", "SP");
        when(repository.salvar(entrada)).thenReturn(endereco);
        new CadastrarEnderecoService(repository).cadastrar(entrada);
        verifyNoInteractions(publisher, externo);
    }
}
