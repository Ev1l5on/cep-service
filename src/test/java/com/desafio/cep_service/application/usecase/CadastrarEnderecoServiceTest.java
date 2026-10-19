package com.desafio.cep_service.application.usecase;

import com.desafio.cep_service.application.exception.EnderecoJaCadastradoException;
import com.desafio.cep_service.application.exception.FalhaPersistenciaEnderecoException;
import com.desafio.cep_service.application.port.out.EnderecoRepositoryPort;
import com.desafio.cep_service.domain.exception.CepInvalidoException;
import com.desafio.cep_service.domain.exception.EnderecoInvalidoException;
import com.desafio.cep_service.domain.model.Endereco;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CadastrarEnderecoServiceTest {
    private final EnderecoRepositoryPort repository = mock(EnderecoRepositoryPort.class);
    private final CadastrarEnderecoService service = new CadastrarEnderecoService(repository);

    @Test
    void cadastroReutilizaPortaPreservandoTodosOsCampos() {
        Endereco entrada = endereco("12345678", "São Paulo", "SP");
        Endereco persistido = endereco("12345-678", "São Paulo", "SP");
        when(repository.salvar(entrada)).thenReturn(persistido);
        assertSame(persistido, service.cadastrar(entrada));
        verify(repository).salvar(entrada);
        verifyNoMoreInteractions(repository);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "1234567", "123456789", "12345-678", "abcdefgh", "' OR '1'='1"})
    void cepInvalidoNaoAcessaPersistencia(String cep) {
        assertThrows(CepInvalidoException.class, () -> service.cadastrar(endereco(cep, "São Paulo", "SP")));
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @MethodSource("enderecosInvalidos")
    void enderecoInvalidoNaoAcessaPersistencia(Endereco endereco) {
        assertThrows(EnderecoInvalidoException.class, () -> service.cadastrar(endereco));
        verifyNoInteractions(repository);
    }

    static Stream<Endereco> enderecosInvalidos() {
        return Stream.of(null,
                endereco("12345678", null, "SP"),
                endereco("12345678", "", "SP"),
                endereco("12345678", "   ", "SP"),
                endereco("12345678", "A".repeat(256), "SP"),
                endereco("12345678", "São Paulo", null),
                endereco("12345678", "São Paulo", "ZZ"),
                endereco("12345678", "São Paulo", "sp"));
    }

    @Test
    void camposOpcionaisPodemSerNulos() {
        Endereco endereco = new Endereco("12345678", null, null, null, "São Paulo", "SP");
        when(repository.salvar(endereco)).thenReturn(endereco);
        assertSame(endereco, service.cadastrar(endereco));
    }

    @Test
    void duplicidadeEhPropagadaSemSobrescreverEnderecoExistente() {
        var duplicado = new EnderecoJaCadastradoException(new RuntimeException("detalhe SQL interno"));
        when(repository.salvar(any())).thenThrow(duplicado);
        assertSame(duplicado, assertThrows(EnderecoJaCadastradoException.class,
                () -> service.cadastrar(endereco("12345678", "São Paulo", "SP"))));
        verify(repository, times(1)).salvar(any());
        verifyNoMoreInteractions(repository);
    }

    @Test
    void falhaDeGravacaoNaoRetornaSucesso() {
        var falha = new FalhaPersistenciaEnderecoException(
                FalhaPersistenciaEnderecoException.Operacao.GRAVACAO,
                FalhaPersistenciaEnderecoException.Motivo.INDISPONIBILIDADE, null);
        when(repository.salvar(any())).thenThrow(falha);
        assertSame(falha, assertThrows(FalhaPersistenciaEnderecoException.class,
                () -> service.cadastrar(endereco("12345678", "São Paulo", "SP"))));
    }

    private static Endereco endereco(String cep, String localidade, String uf) {
        return new Endereco(cep, "Rua manual", "Casa 1", "Centro", localidade, uf);
    }
}
