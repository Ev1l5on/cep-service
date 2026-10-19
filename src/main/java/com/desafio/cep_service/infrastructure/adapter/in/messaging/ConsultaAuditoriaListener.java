package com.desafio.cep_service.infrastructure.adapter.in.messaging;

import com.desafio.cep_service.application.exception.EventoConsultaInvalidoException;
import com.desafio.cep_service.application.usecase.RegistrarConsultaService;
import com.desafio.cep_service.infrastructure.serialization.ConsultaEventoJson;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class ConsultaAuditoriaListener {
    private final ConsultaEventoJson json;
    private final RegistrarConsultaService service;

    public ConsultaAuditoriaListener(ConsultaEventoJson json, RegistrarConsultaService service) {
        this.json = json;
        this.service = service;
    }

    @KafkaListener(id = "consultaAuditoriaListener", topics = "${auditoria.kafka.topic}",
            groupId = "${spring.kafka.consumer.group-id}", containerFactory = "auditoriaKafkaListenerContainerFactory")
    public void consumir(ConsumerRecord<String, String> record) {
        var evento = json.deserializar(record.value());
        if (evento == null || record.key() == null || !record.key().equals(evento.cep())) {
            throw new EventoConsultaInvalidoException();
        }
        try {
            service.registrar(evento);
        } catch (EventoConsultaInvalidoException ex) {
            throw ex;
        } catch (DataIntegrityViolationException ex) {
            throw new EventoConsultaInvalidoException();
        } catch (RuntimeException ex) {
            throw new FalhaProcessamentoAuditoriaException(ex.getClass().getSimpleName());
        }
    }
}
