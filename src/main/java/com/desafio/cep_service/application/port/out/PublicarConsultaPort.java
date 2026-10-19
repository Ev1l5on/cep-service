package com.desafio.cep_service.application.port.out;

import com.desafio.cep_service.application.event.ConsultaCepEvento;

public interface PublicarConsultaPort {
    /** Enfileira sem esperar Kafka ou banco; falhas devem ser diagnosticadas sem afetar a consulta. */
    void publicar(ConsultaCepEvento evento);
}
