package com.desafio.cep_service.infrastructure.adapter.in.messaging;

import com.desafio.cep_service.application.event.ConsultaCepEvento;
import com.desafio.cep_service.application.port.out.ConsultaCepPort;
import com.desafio.cep_service.application.port.out.ConsultaLogRepositoryPort;
import com.desafio.cep_service.application.port.out.EnderecoRepositoryPort;
import com.desafio.cep_service.application.port.out.PublicarConsultaPort;
import com.desafio.cep_service.application.usecase.CadastrarEnderecoService;
import com.desafio.cep_service.application.usecase.CepService;
import com.desafio.cep_service.domain.model.Endereco;
import com.desafio.cep_service.infrastructure.adapter.in.rest.ApiExceptionHandler;
import com.desafio.cep_service.infrastructure.adapter.in.rest.CepController;
import com.desafio.cep_service.infrastructure.adapter.in.rest.EnderecoController;
import com.desafio.cep_service.infrastructure.serialization.ConsultaEventoJson;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
class AuditoriaKafkaIntegrationTest {
    private static final String RUN = UUID.randomUUID().toString().replace("-", "");
    private static final String SCHEMA = "cep_audit_it_" + RUN;
    private static final String TOPIC = "cep-audit-it-" + RUN;
    private static final String DLT = TOPIC + ".DLT";
    private static final String GROUP = "cep-audit-it-" + RUN;

    @DynamicPropertySource
    static void configurar(DynamicPropertyRegistry properties) {
        properties.add("spring.flyway.schemas", () -> SCHEMA);
        properties.add("spring.flyway.default-schema", () -> SCHEMA);
        properties.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        properties.add("spring.datasource.hikari.schema", () -> SCHEMA);
        properties.add("spring.kafka.consumer.group-id", () -> GROUP);
        properties.add("auditoria.kafka.topic", () -> TOPIC);
        properties.add("auditoria.kafka.dlt-topic", () -> DLT);
        properties.add("auditoria.consumer.retry-interval-ms", () -> "100");
    }

    @Autowired EnderecoRepositoryPort enderecos;
    @Autowired CepService consulta;
    @Autowired CadastrarEnderecoService cadastro;
    @Autowired ConsultaEventoJson json;
    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaProperties kafkaProperties;
    @Autowired KafkaListenerEndpointRegistry registry;
    @MockitoSpyBean ConsultaLogRepositoryPort logs;
    @MockitoSpyBean PublicarConsultaPort publisher;
    @MockitoSpyBean KafkaTemplate<String, String> kafka;

    @BeforeEach
    void aguardarConsumidor() throws Exception {
        await(() -> {
            var container = registry.getListenerContainer("consultaAuditoriaListener");
            return container != null && container.getAssignedPartitions() != null
                    && container.getAssignedPartitions().size() == 3;
        });
    }

    @Test
    void getDatabasePublicaConsomeEPersisteSnapshot() throws Exception {
        String cep = "81000001";
        enderecos.salvar(endereco(cep));
        var mvc = MockMvcBuilders.standaloneSetup(new CepController(consulta))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(get("/api/ceps/" + cep)).andExpect(status().isOk())
                .andExpect(jsonPath("$.erro").isEmpty());
        await(() -> countCep(cep) == 1);
        assertEquals("DATABASE", jdbc.queryForObject("SELECT source FROM consulta_logs WHERE cep = ?", String.class, cep));
        String snapshot = jdbc.queryForObject("SELECT response_data::text FROM consulta_logs WHERE cep = ?", String.class, cep);
        var node = JsonMapper.builder().build().readTree(snapshot);
        assertEquals(6, node.size());
        assertEquals(endereco(cep).logradouro(), node.get("logradouro").asString());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM consulta_logs WHERE cep = ? "
                + "AND event_version = 1 AND consulted_at IS NOT NULL AND created_at IS NOT NULL", Integer.class, cep));
    }

    @Test
    void getViaCepESegundaConsultaProduzemOrigensDistintasSemViaCepPublico() throws Exception {
        String cep = "81000002";
        var externo = mock(ConsultaCepPort.class);
        when(externo.buscar(cep)).thenReturn(Optional.of(endereco(cep)));
        var service = new CepService(externo, enderecos, publisher);
        assertEquals(endereco(cep), service.buscar(cep));
        assertEquals(endereco(cep), service.buscar(cep));
        await(() -> countCep(cep) == 2);
        assertEquals(java.util.Set.of("VIACEP", "DATABASE"), java.util.Set.copyOf(
                jdbc.queryForList("SELECT source FROM consulta_logs WHERE cep = ?", String.class, cep)));
        verify(externo, times(1)).buscar(cep);
    }

    @Test
    void postNaoEnfileiraEvento() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new EnderecoController(cadastro))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(post("/api/enderecos").contentType("application/json").content("""
                {"cep":"81000003","logradouro":"Rua manual","localidade":"São Paulo","uf":"SP"}
                """)).andExpect(status().isCreated());
        verifyNoInteractions(publisher);
        assertEquals(0, countCep("81000003"));
    }

    @Test
    void reentregaDoMesmoEventIdMantemUmaLinhaEConfirmaAmbosOffsets() throws Exception {
        var event = evento("81000004");
        enviar(event);
        var second = enviar(event);
        await(() -> countId(event.eventId()) == 1);
        await(() -> committed(second) > second.getRecordMetadata().offset());
        assertEquals(1, countId(event.eventId()));
        assertEquals(event.consultedAt(), jdbc.queryForObject(
                "SELECT consulted_at FROM consulta_logs WHERE event_id = ?",
                (rs, row) -> rs.getObject(1, OffsetDateTime.class).toInstant(), event.eventId()));
    }

    @Test
    void falhaTemporariaDoBancoEhReprocessadaAteSucesso() throws Exception {
        var event = evento("81000005");
        var attempts = new AtomicInteger();
        doAnswer(invocation -> {
            if (attempts.incrementAndGet() < 3) {
                throw new DataAccessResourceFailureException("credencial=nao-deve-vazar");
            }
            return invocation.callRealMethod();
        }).when(logs).salvar(argThat(e -> e.eventId().equals(event.eventId())));
        var result = enviar(event);
        await(() -> countId(event.eventId()) == 1);
        await(() -> committed(result) > result.getRecordMetadata().offset());
        assertEquals(3, attempts.get());
    }

    @Test
    void falhaPersistenteVaiParaDltAposLimiteDeRetry() throws Exception {
        var event = evento("81000006");
        doThrow(new DataAccessResourceFailureException("falha temporária"))
                .when(logs).salvar(argThat(e -> e.eventId().equals(event.eventId())));
        var result = enviar(event);
        var dead = aguardarDlt(json.serializar(event));
        assertEquals(event.cep(), dead.key());
        assertNotNull(dead.headers().lastHeader("audit-failure-type"));
        assertNull(dead.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_STACKTRACE));
        assertNull(dead.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_MESSAGE));
        verify(logs, times(3)).salvar(argThat(e -> e.eventId().equals(event.eventId())));
        assertEquals(0, countId(event.eventId()));
        await(() -> committed(result) > result.getRecordMetadata().offset());
    }

    @Test
    void eventoInvalidoVaiDiretoParaDltSemPersistir() throws Exception {
        var event = evento("81000007");
        String payload = json.serializar(event).replace("\"eventVersion\":1", "\"eventVersion\":99");
        var result = kafka.send(TOPIC, event.cep(), payload).get(10, TimeUnit.SECONDS);
        assertEquals(payload, aguardarDlt(payload).value());
        verify(logs, never()).salvar(argThat(e -> e.eventId().equals(event.eventId())));
        assertEquals(0, countId(event.eventId()));
        await(() -> committed(result) > result.getRecordMetadata().offset());
    }

    @Test
    void persistenciaPendenteNaoConfirmaOffsetPrematuramente() throws Exception {
        var event = evento("81000008");
        var entrou = new CountDownLatch(1);
        var liberar = new CountDownLatch(1);
        doAnswer(invocation -> {
            entrou.countDown();
            assertTrue(liberar.await(15, TimeUnit.SECONDS));
            return invocation.callRealMethod();
        }).when(logs).salvar(argThat(e -> e.eventId().equals(event.eventId())));
        try {
            var result = enviar(event);
            assertTrue(entrou.await(10, TimeUnit.SECONDS));
            assertEquals(0, countId(event.eventId()));
            assertTrue(committed(result) <= result.getRecordMetadata().offset());
            liberar.countDown();
            await(() -> countId(event.eventId()) == 1);
            await(() -> committed(result) > result.getRecordMetadata().offset());
        } finally {
            liberar.countDown();
        }
    }

    @Test
    void falhaNoEnvioDltNaoConfirmaOffsetAteDltEstarDisponivel() throws Exception {
        var event = evento("81000009");
        String payload = json.serializar(event).replace("\"eventVersion\":1", "\"eventVersion\":99");
        var falhar = new AtomicBoolean(true);
        var tentativasDlt = new AtomicInteger();
        doAnswer(invocation -> {
            ProducerRecord<String, String> record = invocation.getArgument(0);
            if (record.topic().equals(DLT) && record.value().equals(payload) && falhar.get()) {
                tentativasDlt.incrementAndGet();
                return CompletableFuture.<SendResult<String, String>>failedFuture(new IllegalStateException("DLT indisponível"));
            }
            return invocation.callRealMethod();
        }).when(kafka).send(any(ProducerRecord.class));
        try {
            var result = kafka.send(TOPIC, event.cep(), payload).get(10, TimeUnit.SECONDS);
            await(() -> tentativasDlt.get() > 0);
            assertTrue(committed(result) <= result.getRecordMetadata().offset());
            assertEquals(0, countId(event.eventId()));
            falhar.set(false);
            assertEquals(payload, aguardarDlt(payload).value());
            await(() -> committed(result) > result.getRecordMetadata().offset());
        } finally {
            falhar.set(false);
        }
    }

    @Test
    void migracaoV2TemRestricaoUnicaEJsonb() {
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version = '2' AND success", Integer.class));
        assertEquals("jsonb", jdbc.queryForObject("SELECT data_type FROM information_schema.columns "
                + "WHERE table_schema = ? AND table_name = 'consulta_logs' AND column_name = 'response_data'", String.class, SCHEMA));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM information_schema.table_constraints "
                + "WHERE table_schema = ? AND constraint_name = 'uk_consulta_logs_event_id'", Integer.class, SCHEMA));
    }

    private SendResult<String, String> enviar(ConsultaCepEvento event) throws Exception {
        return kafka.send(TOPIC, event.cep(), json.serializar(event)).get(10, TimeUnit.SECONDS);
    }

    private long committed(SendResult<String, String> result) {
        try (var admin = AdminClient.create(java.util.Map.of("bootstrap.servers", kafkaProperties.getBootstrapServers()))) {
            var offsets = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
            var offset = offsets.get(new TopicPartition(TOPIC, result.getRecordMetadata().partition()));
            return offset == null ? -1 : offset.offset();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private ConsumerRecord<String, String> aguardarDlt(String payload) {
        var properties = new HashMap<>(kafkaProperties.buildConsumerProperties());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-observer-" + UUID.randomUUID());
        try (var consumer = new KafkaConsumer<String, String>(properties)) {
            var partitions = List.of(new TopicPartition(DLT, 0), new TopicPartition(DLT, 1), new TopicPartition(DLT, 2));
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (System.nanoTime() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(200))) {
                    if (payload.equals(record.value())) { return record; }
                }
            }
            throw new AssertionError("Mensagem não encontrada na DLT");
        }
    }

    private int countCep(String cep) {
        return jdbc.queryForObject("SELECT count(*) FROM consulta_logs WHERE cep = ?", Integer.class, cep);
    }

    private int countId(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM consulta_logs WHERE event_id = ?", Integer.class, id);
    }

    private static Endereco endereco(String cep) {
        return new Endereco(cep.substring(0, 5) + "-" + cep.substring(5), "Rua de teste", "", "Centro", "São Paulo", "SP");
    }

    private static ConsultaCepEvento evento(String cep) {
        // PostgreSQL armazena microssegundos; estabiliza a comparação exata do round-trip.
        return new ConsultaCepEvento(UUID.randomUUID(), 1, cep,
                java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                ConsultaCepEvento.Source.DATABASE, endereco(cep));
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) { throw new AssertionError("Condição assíncrona não satisfeita"); }
            Thread.sleep(50);
        }
    }
}
