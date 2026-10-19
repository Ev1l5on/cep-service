package com.desafio.cep_service.infrastructure.adapter.out.messaging;

import com.desafio.cep_service.application.event.ConsultaCepEvento;
import com.desafio.cep_service.infrastructure.serialization.ConsultaEventoJson;
import com.desafio.cep_service.application.port.out.PublicarConsultaPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;

@Component
public class KafkaConsultaPublisher implements PublicarConsultaPort {
    private static final Logger log = LoggerFactory.getLogger(KafkaConsultaPublisher.class);
    private final KafkaTemplate<String, String> kafka;
    private final ConsultaEventoJson json;
    private final Executor executor;
    private final String topic;

    public KafkaConsultaPublisher(KafkaTemplate<String, String> kafka, ConsultaEventoJson json,
            @Qualifier("auditoriaPublisherExecutor") Executor executor,
            @Value("${auditoria.kafka.topic}") String topic) {
        this.kafka = kafka;
        this.json = json;
        this.executor = executor;
        this.topic = topic;
    }

    @Override
    public void publicar(ConsultaCepEvento evento) {
        try {
            executor.execute(() -> enviar(evento));
        } catch (RuntimeException ex) {
            falha(evento, "ENFILEIRAMENTO", ex);
        }
    }

    private void enviar(ConsultaCepEvento evento) {
        try {
            kafka.send(topic, evento.cep(), json.serializar(evento)).whenComplete((result, failure) -> {
                if (failure != null) {
                    falha(evento, "CONFIRMACAO", failure);
                }
            });
        } catch (RuntimeException ex) {
            falha(evento, "ENVIO", ex);
        }
    }

    private void falha(ConsultaCepEvento evento, String etapa, Throwable ex) {
        log.error("Falha de publicação: eventId={} cep={} etapa={} tipo={}",
                evento.eventId(), evento.cep(), etapa, ex.getClass().getSimpleName());
    }
}
