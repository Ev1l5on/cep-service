package com.desafio.cep_service.infrastructure.adapter.in.messaging;

import com.desafio.cep_service.application.event.ConsultaCepEvento;
import com.desafio.cep_service.application.exception.EventoConsultaInvalidoException;
import com.desafio.cep_service.application.usecase.RegistrarConsultaService;
import com.desafio.cep_service.domain.model.Endereco;
import com.desafio.cep_service.infrastructure.serialization.ConsultaEventoJson;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConsultaAuditoriaListenerTest {
    private final RegistrarConsultaService service = mock(RegistrarConsultaService.class);
    private final ConsultaEventoJson json = new ConsultaEventoJson();
    private final ConsultaAuditoriaListener listener = new ConsultaAuditoriaListener(json, service);
    private final ConsultaCepEvento event = ConsultaCepEvento.novo("01001000", ConsultaCepEvento.Source.DATABASE,
            new Endereco("01001-000", "Rua", "", "Centro", "São Paulo", "SP"));

    @Test
    void delegaProcessamentoParaCasoDeUso() {
        listener.consumir(record("01001000", json.serializar(event)));
        verify(service).registrar(event);
    }

    @Test
    void chaveInconsistenteNaoPersiste() {
        assertThrows(EventoConsultaInvalidoException.class,
                () -> listener.consumir(record("99999999", json.serializar(event))));
        verifyNoInteractions(service);
    }

    @Test
    void jsonInvalidoNaoPersiste() {
        assertThrows(EventoConsultaInvalidoException.class, () -> listener.consumir(record("01001000", "{")));
        verifyNoInteractions(service);
    }

    @Test
    void falhaDoBancoPropagaExcecaoSeguraParaRetrySemConfirmarConsumo() {
        doThrow(new DataAccessResourceFailureException("SQL password=segredo-interno")).when(service).registrar(event);
        var ex = assertThrows(FalhaProcessamentoAuditoriaException.class,
                () -> listener.consumir(record("01001000", json.serializar(event))));
        assertFalse(ex.getMessage().contains("segredo-interno"));
        assertTrue(ex.getMessage().contains("DataAccessResourceFailureException"));
        assertNull(ex.getCause());
    }

    private ConsumerRecord<String, String> record(String key, String payload) {
        return new ConsumerRecord<>("consulta", 0, 0, key, payload);
    }
}
