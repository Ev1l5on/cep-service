package com.desafio.cep_service.infrastructure.adapter.out.persistence;

import com.desafio.cep_service.application.exception.EnderecoJaCadastradoException;
import com.desafio.cep_service.application.exception.FalhaPersistenciaEnderecoException;
import com.desafio.cep_service.application.port.out.EnderecoRepositoryPort;
import com.desafio.cep_service.domain.model.Endereco;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

import static com.desafio.cep_service.application.exception.FalhaPersistenciaEnderecoException.Motivo.*;
import static com.desafio.cep_service.application.exception.FalhaPersistenciaEnderecoException.Operacao.*;

@Component
public class EnderecoPersistenceAdapter implements EnderecoRepositoryPort {
    private final EnderecoJpaRepository repository;
    private final TransactionTemplate leitura;
    private final TransactionTemplate gravacao;

    public EnderecoPersistenceAdapter(EnderecoJpaRepository repository, PlatformTransactionManager manager) {
        this.repository = repository;
        leitura = new TransactionTemplate(manager);
        leitura.setReadOnly(true);
        leitura.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        gravacao = new TransactionTemplate(manager);
        gravacao.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public Optional<Endereco> buscarPorCep(String cep) {
        try {
            return Objects.requireNonNull(leitura.execute(status -> repository.findByCep(cep)
                    .map(EnderecoEntity::toDomain)));
        } catch (DataAccessException | TransactionException ex) {
            throw traduzir(LEITURA, ex);
        }
    }

    @Override
    public Endereco salvar(Endereco endereco) {
        try {
            return Objects.requireNonNull(gravacao.execute(status -> repository
                    .saveAndFlush(new EnderecoEntity(endereco)).toDomain()));
        } catch (DataIntegrityViolationException ex) {
            // O execute já realizou rollback. Somente esta constraint representa concorrência esperada.
            if (conflitoDeCep(ex)) {
                throw new EnderecoJaCadastradoException(ex);
            }
            throw traduzir(GRAVACAO, ex);
        } catch (DataAccessException | TransactionException ex) {
            throw traduzir(GRAVACAO, ex);
        }
    }

    private boolean conflitoDeCep(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException constraint
                    && "23505".equals(constraint.getSQLState())
                    && "uk_enderecos_cep".equals(constraint.getConstraintName())) {
                return true;
            }
        }
        return false;
    }

    private FalhaPersistenciaEnderecoException traduzir(
            FalhaPersistenciaEnderecoException.Operacao operacao, Throwable ex) {
        boolean indisponivel = false;
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof DataAccessResourceFailureException
                    || cause instanceof TransientDataAccessException
                    || cause instanceof CannotCreateTransactionException
                    || cause instanceof TransactionTimedOutException) {
                indisponivel = true;
            }
            if (cause instanceof SQLException sql && sql.getSQLState() != null
                    && (sql.getSQLState().startsWith("08") || sql.getSQLState().startsWith("53")
                    || sql.getSQLState().matches("57P0[123]") || "57014".equals(sql.getSQLState()))) {
                indisponivel = true;
            }
        }
        return new FalhaPersistenciaEnderecoException(operacao, indisponivel ? INDISPONIBILIDADE : ERRO, ex);
    }
}
