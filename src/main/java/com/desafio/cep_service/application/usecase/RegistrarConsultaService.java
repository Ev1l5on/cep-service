package com.desafio.cep_service.application.usecase;

import com.desafio.cep_service.application.event.ConsultaCepEvento;
import com.desafio.cep_service.application.exception.EventoConsultaInvalidoException;
import com.desafio.cep_service.application.port.out.ConsultaLogRepositoryPort;

public class RegistrarConsultaService {
    private final ConsultaLogRepositoryPort repository;

    public RegistrarConsultaService(ConsultaLogRepositoryPort repository) {
        this.repository = repository;
    }

    public void registrar(ConsultaCepEvento evento) {
        if (evento == null || evento.eventId() == null || evento.eventVersion() != 1
                || evento.cep() == null || !evento.cep().matches("[0-9]{8}")
                || evento.consultedAt() == null || evento.source() == null || evento.responseData() == null) {
            throw new EventoConsultaInvalidoException();
        }
        var endereco = evento.responseData();
        if (endereco.cep() == null || !endereco.cep().matches("[0-9]{5}-?[0-9]{3}")
                || !endereco.cep().replace("-", "").equals(evento.cep())
                || endereco.localidade() == null || endereco.localidade().isBlank()
                || endereco.uf() == null || !endereco.uf().matches(
                        "AC|AL|AP|AM|BA|CE|DF|ES|GO|MA|MT|MS|MG|PA|PB|PR|PE|PI|RJ|RN|RS|RO|RR|SC|SP|SE|TO")) {
            throw new EventoConsultaInvalidoException();
        }
        repository.salvar(evento);
    }
}
