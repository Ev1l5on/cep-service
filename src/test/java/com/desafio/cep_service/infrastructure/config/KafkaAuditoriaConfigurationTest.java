package com.desafio.cep_service.infrastructure.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class KafkaAuditoriaConfigurationTest {
    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void filaDePublicacaoDeveSerLimitadaEPositiva(int capacity) {
        assertThrows(IllegalArgumentException.class,
                () -> new KafkaAuditoriaConfiguration().auditoriaPublisherExecutor(capacity));
    }

    @ParameterizedTest
    @CsvSource({"0,2", "100,-1", "100,11", "100,9223372036854775807"})
    void rejeitaRetriesIlimitadosOuConfiguracaoInvalida(long interval, long retries) {
        assertThrows(IllegalArgumentException.class, () -> new KafkaAuditoriaConfiguration()
                .auditoriaErrorHandler(mock(DeadLetterPublishingRecoverer.class), interval, retries));
    }
}
