package com.desafio.cep_service.infrastructure.adapter.out.messaging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.desafio.cep_service.application.event.ConsultaCepEvento;
import com.desafio.cep_service.application.port.out.ConsultaCepPort;
import com.desafio.cep_service.application.port.out.EnderecoRepositoryPort;
import com.desafio.cep_service.application.usecase.CepService;
import com.desafio.cep_service.domain.model.Endereco;
import com.desafio.cep_service.infrastructure.adapter.in.rest.ApiExceptionHandler;
import com.desafio.cep_service.infrastructure.adapter.in.rest.CepController;
import com.desafio.cep_service.infrastructure.serialization.ConsultaEventoJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class KafkaConsultaPublisherTest {
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final ConsultaEventoJson json = new ConsultaEventoJson();
    private final Endereco endereco = new Endereco("01001-000", "Praça da Sé", "", "Sé", "São Paulo", "SP");
    private final ConsultaCepEvento event = ConsultaCepEvento.novo("01001000", ConsultaCepEvento.Source.DATABASE, endereco);

    @Test
    void httpRetornaAntesDoEnvioEDaConfirmacaoKafka() throws Exception {
        var queue = new ArrayList<Runnable>();
        var pending = new CompletableFuture<SendResult<String, String>>();
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(pending);
        var publisher = new KafkaConsultaPublisher(kafka, json, queue::add, "consultas");
        var repo = mock(EnderecoRepositoryPort.class);
        when(repo.buscarPorCep("01001000")).thenReturn(Optional.of(endereco));
        var mvc = MockMvcBuilders.standaloneSetup(new CepController(
                new CepService(mock(ConsultaCepPort.class), repo, publisher)))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(get("/api/ceps/01001000")).andExpect(status().isOk())
                .andExpect(jsonPath("$.cep").value("01001-000"));
        verifyNoInteractions(kafka);
        assertEquals(1, queue.size());
        queue.getFirst().run();
        assertFalse(pending.isDone());
        verify(kafka).send(eq("consultas"), eq("01001000"), argThat(payload ->
                json.deserializar(payload).responseData().equals(endereco)));
    }

    @Test
    void filaCheiaNaoPropagaErroERegistraDiagnosticoSeguro() {
        registrarFalhaSegura(() -> new KafkaConsultaPublisher(kafka, json,
                task -> { throw new RejectedExecutionException("token=segredo-interno"); }, "consultas").publicar(event));
        verifyNoInteractions(kafka);
    }

    @Test
    void falhaImediataDoKafkaEhTratadaNoWorker() {
        when(kafka.send(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("segredo-interno"));
        registrarFalhaSegura(() -> new KafkaConsultaPublisher(kafka, json, Runnable::run, "consultas").publicar(event));
    }

    @Test
    void falhaAssincronaEhDiagnosticadaSemVazarMensagemTecnica() {
        var pending = new CompletableFuture<SendResult<String, String>>();
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(pending);
        registrarFalhaSegura(() -> {
            new KafkaConsultaPublisher(kafka, json, Runnable::run, "consultas").publicar(event);
            pending.completeExceptionally(new RuntimeException("token=segredo-interno"));
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void falhaImediataOuAssincronaDoProducerMantemHttp200EContrato(boolean imediata) throws Exception {
        if (imediata) {
            when(kafka.send(anyString(), anyString(), anyString()))
                    .thenThrow(new IllegalStateException("segredo-interno"));
        } else {
            when(kafka.send(anyString(), anyString(), anyString())).thenReturn(
                    CompletableFuture.failedFuture(new IllegalStateException("segredo-interno")));
        }
        var publisher = new KafkaConsultaPublisher(kafka, json, Runnable::run, "consultas");
        var repo = mock(EnderecoRepositoryPort.class);
        when(repo.buscarPorCep("01001000")).thenReturn(Optional.of(endereco));
        var mvc = MockMvcBuilders.standaloneSetup(new CepController(
                        new CepService(mock(ConsultaCepPort.class), repo, publisher)))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(get("/api/ceps/01001000")).andExpect(status().isOk())
                .andExpect(content().json("""
                        {"cep":"01001-000","logradouro":"Praça da Sé","complemento":"",
                         "bairro":"Sé","localidade":"São Paulo","uf":"SP","erro":null}
                        """, org.springframework.test.json.JsonCompareMode.STRICT));
    }

    private void registrarFalhaSegura(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(KafkaConsultaPublisher.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertDoesNotThrow(action::run);
            assertEquals(1, appender.list.size());
            var entry = appender.list.getFirst();
            assertTrue(entry.getFormattedMessage().contains(event.eventId().toString()));
            assertFalse(entry.getFormattedMessage().contains("segredo-interno"));
            assertNull(entry.getThrowableProxy());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
