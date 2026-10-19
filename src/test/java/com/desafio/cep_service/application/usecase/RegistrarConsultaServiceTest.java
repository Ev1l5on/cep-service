package com.desafio.cep_service.application.usecase;

import com.desafio.cep_service.application.event.ConsultaCepEvento;
import com.desafio.cep_service.application.exception.EventoConsultaInvalidoException;
import com.desafio.cep_service.application.port.out.ConsultaLogRepositoryPort;
import com.desafio.cep_service.domain.model.Endereco;
import com.desafio.cep_service.infrastructure.serialization.ConsultaEventoJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RegistrarConsultaServiceTest {
    private final ConsultaLogRepositoryPort repository = mock(ConsultaLogRepositoryPort.class);
    private final RegistrarConsultaService service = new RegistrarConsultaService(repository);
    private final ConsultaEventoJson json = new ConsultaEventoJson();
    private static final Endereco ENDERECO = new Endereco("01001-000", "Praça da Sé", "", "Sé", "São Paulo", "SP");

    @Test
    void contratoJsonVersionadoPreservaHorarioUtcESeisCamposDoEndereco() {
        var event = ConsultaCepEvento.novo("01001000", ConsultaCepEvento.Source.DATABASE, ENDERECO);
        String payload = json.serializar(event);
        var root = JsonMapper.builder().build().readTree(payload);
        assertEquals(6, root.size());
        assertEquals(6, root.get("responseData").size());
        assertEquals(1, root.get("eventVersion").asInt());
        assertEquals(event.eventId(), UUID.fromString(root.get("eventId").asString()));
        assertEquals(event.consultedAt(), Instant.parse(root.get("consultedAt").asString()));
        assertTrue(root.get("consultedAt").asString().endsWith("Z"));
        assertEquals(event, json.deserializar(payload));
        service.registrar(event);
        verify(repository).salvar(event);
    }

    @ParameterizedTest
    @MethodSource("invalidos")
    void eventoInvalidoNaoAcessaBanco(ConsultaCepEvento event) {
        assertThrows(EventoConsultaInvalidoException.class, () -> service.registrar(event));
        verifyNoInteractions(repository);
    }

    static Stream<ConsultaCepEvento> invalidos() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        var source = ConsultaCepEvento.Source.VIACEP;
        return Stream.of(null,
                new ConsultaCepEvento(null, 1, "01001000", now, source, ENDERECO),
                new ConsultaCepEvento(id, 2, "01001000", now, source, ENDERECO),
                new ConsultaCepEvento(id, 0, "01001000", now, source, ENDERECO),
                new ConsultaCepEvento(id, 1, null, now, source, ENDERECO),
                new ConsultaCepEvento(id, 1, "01001-000", now, source, ENDERECO),
                new ConsultaCepEvento(id, 1, "01001000", null, source, ENDERECO),
                new ConsultaCepEvento(id, 1, "01001000", now, null, ENDERECO),
                new ConsultaCepEvento(id, 1, "01001000", now, source, null),
                new ConsultaCepEvento(id, 1, "01001000", now, source,
                        new Endereco("99999-999", "Rua", "", "", "São Paulo", "SP")),
                new ConsultaCepEvento(id, 1, "01001000", now, source,
                        new Endereco("01001-000", "Rua", "", "", "", "SP")),
                new ConsultaCepEvento(id, 1, "01001000", now, source,
                        new Endereco("01001-000", "Rua", "", "", "São Paulo", "ZZ")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "[]", "{\"eventVersion\":1.2}", "{\"eventVersion\":\"1\"}",
            "{\"source\":\"OTHER\"}", "{\"password\":\"segredo-interno\"}"})
    void jsonInvalidoNaoVazaPayload(String payload) {
        var ex = assertThrows(EventoConsultaInvalidoException.class, () -> json.deserializar(payload));
        assertFalse(ex.getMessage().contains("segredo-interno"));
        assertNull(ex.getCause());
    }

    @Test
    void falhaDePersistenciaNaoEhSilenciada() {
        var event = ConsultaCepEvento.novo("01001000", ConsultaCepEvento.Source.DATABASE, ENDERECO);
        RuntimeException failure = new RuntimeException("falha temporária");
        doThrow(failure).when(repository).salvar(event);
        assertSame(failure, assertThrows(RuntimeException.class, () -> service.registrar(event)));
    }
}
