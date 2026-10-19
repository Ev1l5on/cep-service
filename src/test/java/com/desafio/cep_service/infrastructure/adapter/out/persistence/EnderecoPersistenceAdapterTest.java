package com.desafio.cep_service.infrastructure.adapter.out.persistence;

import com.desafio.cep_service.application.exception.EnderecoJaCadastradoException;
import com.desafio.cep_service.application.exception.FalhaPersistenciaEnderecoException;
import com.desafio.cep_service.domain.model.Endereco;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.sql.SQLException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EnderecoPersistenceAdapterTest {
    private final EnderecoJpaRepository repository = mock(EnderecoJpaRepository.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final EnderecoPersistenceAdapter adapter = new EnderecoPersistenceAdapter(repository, manager);
    private final Endereco endereco = new Endereco("01001-000", "Praça da Sé", "", "Sé", "São Paulo", "SP");

    @BeforeEach
    void setup() {
        when(manager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
    }

    @Test
    void ausenciaConfirmadaRetornaOptionalVazioEmTransacaoSomenteLeitura() {
        when(repository.findByCep("01001000")).thenReturn(Optional.empty());
        assertTrue(adapter.buscarPorCep("01001000").isEmpty());
        verify(manager).getTransaction(argThat(tx -> tx.isReadOnly()
                && tx.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        verify(manager).commit(any());
    }

    @Test
    void falhaDeLeituraNaoViraAusencia() {
        when(repository.findByCep(any())).thenThrow(new DataAccessResourceFailureException("SQL segredo"));
        var ex = assertThrows(FalhaPersistenciaEnderecoException.class, () -> adapter.buscarPorCep("01001000"));
        assertEquals(FalhaPersistenciaEnderecoException.Operacao.LEITURA, ex.getOperacao());
        assertEquals(FalhaPersistenciaEnderecoException.Motivo.INDISPONIBILIDADE, ex.getMotivo());
        assertFalse(ex.getMessage().contains("segredo"));
        verify(manager).rollback(any());
    }

    @Test
    void falhaDeAberturaDeTransacaoEhIndisponibilidade() {
        when(manager.getTransaction(any())).thenThrow(new CannotCreateTransactionException("password=segredo"));
        var ex = assertThrows(FalhaPersistenciaEnderecoException.class, () -> adapter.buscarPorCep("01001000"));
        assertEquals(FalhaPersistenciaEnderecoException.Motivo.INDISPONIBILIDADE, ex.getMotivo());
        verifyNoInteractions(repository);
    }

    @Test
    void falhaDeGravacaoEhExplicita() {
        when(repository.saveAndFlush(any())).thenThrow(new DataAccessResourceFailureException("connection refused"));
        var ex = assertThrows(FalhaPersistenciaEnderecoException.class, () -> adapter.salvar(endereco));
        assertEquals(FalhaPersistenciaEnderecoException.Operacao.GRAVACAO, ex.getOperacao());
        assertEquals(FalhaPersistenciaEnderecoException.Motivo.INDISPONIBILIDADE, ex.getMotivo());
    }

    @Test
    void erroDeIntegridadeNaoRelacionadoAoCepNaoEhTratadoComoConcorrencia() {
        when(repository.saveAndFlush(any())).thenThrow(violacao("23502", "ck_enderecos_localidade"));
        var ex = assertThrows(FalhaPersistenciaEnderecoException.class, () -> adapter.salvar(endereco));
        assertEquals(FalhaPersistenciaEnderecoException.Motivo.ERRO, ex.getMotivo());
    }

    @Test
    void uniqueDeOutraConstraintNaoEhConflitoDeCep() {
        when(repository.saveAndFlush(any())).thenThrow(violacao("23505", "enderecos_pkey"));
        assertThrows(FalhaPersistenciaEnderecoException.class, () -> adapter.salvar(endereco));
    }

    @Test
    void conflitoEhTraduzidoSomenteDepoisDoRollbackEPermiteNovaLeitura() {
        when(repository.saveAndFlush(any())).thenThrow(violacao("23505", "uk_enderecos_cep"));
        when(repository.findByCep("01001000")).thenReturn(Optional.of(new EnderecoEntity(endereco)));
        assertThrows(EnderecoJaCadastradoException.class, () -> adapter.salvar(endereco));
        assertEquals(endereco, adapter.buscarPorCep("01001000").orElseThrow());
        var order = inOrder(manager, repository);
        order.verify(manager).getTransaction(any());
        order.verify(repository).saveAndFlush(any());
        order.verify(manager).rollback(any());
        order.verify(manager).getTransaction(any());
        order.verify(repository).findByCep("01001000");
        order.verify(manager).commit(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"08006", "53300", "57P01", "57014"})
    void sqlStateDeIndisponibilidadeEhTraduzidoSemExporSql(String state) {
        when(repository.findByCep(any())).thenThrow(new DataIntegrityViolationException(
                "SELECT * FROM enderecos segredo", new SQLException("segredo", state)));
        var ex = assertThrows(FalhaPersistenciaEnderecoException.class, () -> adapter.buscarPorCep("01001000"));
        assertEquals(FalhaPersistenciaEnderecoException.Motivo.INDISPONIBILIDADE, ex.getMotivo());
        assertFalse(ex.getMessage().contains("enderecos"));
    }

    private DataIntegrityViolationException violacao(String state, String constraint) {
        return new DataIntegrityViolationException("detalhe SQL interno",
                new ConstraintViolationException("detalhe interno", new SQLException("interno", state), constraint));
    }
}
