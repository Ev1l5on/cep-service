package com.desafio.cep_service.application.usecase;

import com.desafio.cep_service.application.port.out.ConsultaCepPort;
import com.desafio.cep_service.domain.exception.CepInvalidoException;
import com.desafio.cep_service.domain.exception.CepNaoEncontradoException;
import com.desafio.cep_service.domain.model.Endereco;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CepServiceTest {
    private final ConsultaCepPort port = mock(ConsultaCepPort.class);
    private final CepService service = new CepService(port);

    @Test
    void retornaEnderecoEDelegaUmaVezParaPortaExterna() {
        Endereco endereco = new Endereco("01001-000", "Praça da Sé", "lado ímpar", "Sé", "São Paulo", "SP");
        when(port.buscar("01001000")).thenReturn(Optional.of(endereco));

        assertSame(endereco, service.buscar("01001000"));
        verify(port).buscar("01001000");
        verifyNoMoreInteractions(port);
    }

    @Test
    void naoConsultaPortaQuandoCepInvalido() {
        assertThrows(CepInvalidoException.class, () -> service.buscar("01001-000"));
        verifyNoInteractions(port);
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
}
