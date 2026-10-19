package com.desafio.cep_service.application.event;

import com.desafio.cep_service.domain.model.Endereco;
import java.time.Instant;
import java.util.UUID;

public record ConsultaCepEvento(UUID eventId, int eventVersion, String cep, Instant consultedAt,
                                Source source, Endereco responseData) {
    public enum Source { DATABASE, VIACEP }

    public static ConsultaCepEvento novo(String cep, Source source, Endereco endereco) {
        return new ConsultaCepEvento(UUID.randomUUID(), 1, cep, Instant.now(), source, endereco);
    }
}
