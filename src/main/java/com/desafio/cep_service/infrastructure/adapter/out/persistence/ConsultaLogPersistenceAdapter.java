package com.desafio.cep_service.infrastructure.adapter.out.persistence;

import com.desafio.cep_service.application.event.ConsultaCepEvento;
import com.desafio.cep_service.application.port.out.ConsultaLogRepositoryPort;
import com.desafio.cep_service.infrastructure.serialization.ConsultaEventoJson;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;

@Component
public class ConsultaLogPersistenceAdapter implements ConsultaLogRepositoryPort {
    private static final String INSERT = """
            INSERT INTO consulta_logs (event_id, event_version, cep, consulted_at, source, response_data)
            VALUES (?, ?, ?, ?, ?, CAST(? AS JSONB))
            ON CONFLICT (event_id) DO NOTHING
            """;
    private final JdbcTemplate jdbc;
    private final ConsultaEventoJson json;
    private final TransactionTemplate transaction;

    public ConsultaLogPersistenceAdapter(JdbcTemplate jdbc, ConsultaEventoJson json, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.json = json;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void salvar(ConsultaCepEvento evento) {
        String enderecoJson = json.serializarEndereco(evento.responseData());
        transaction.executeWithoutResult(status -> jdbc.update(INSERT,
                evento.eventId(), evento.eventVersion(), evento.cep(), Timestamp.from(evento.consultedAt()),
                evento.source().name(), enderecoJson));
    }
}
