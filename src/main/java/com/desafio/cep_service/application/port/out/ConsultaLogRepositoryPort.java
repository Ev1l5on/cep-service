package com.desafio.cep_service.application.port.out;

import com.desafio.cep_service.application.event.ConsultaCepEvento;

public interface ConsultaLogRepositoryPort {
    /** Confirma a gravação antes de retornar; reentrega do mesmo eventId não cria outro log. */
    void salvar(ConsultaCepEvento evento);
}
