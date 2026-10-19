package com.desafio.cep_service.infrastructure.serialization;

import com.desafio.cep_service.application.event.ConsultaCepEvento;
import com.desafio.cep_service.application.exception.EventoConsultaInvalidoException;
import com.desafio.cep_service.domain.model.Endereco;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

@Component
public class ConsultaEventoJson {
    private final JsonMapper mapper = JsonMapper.builder()
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .withCoercionConfig(LogicalType.Textual, config -> config
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .withCoercionConfig(LogicalType.Integer, config -> config
                    .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail))
            .build();

    public String serializar(ConsultaCepEvento evento) {
        return mapper.writeValueAsString(evento);
    }

    public ConsultaCepEvento deserializar(String json) {
        try {
            if (json == null) { throw new EventoConsultaInvalidoException(); }
            return mapper.readValue(json, ConsultaCepEvento.class);
        } catch (JacksonException ex) {
            // Não propagar mensagens do parser que possam incluir o payload recebido.
            throw new EventoConsultaInvalidoException();
        }
    }

    public String serializarEndereco(Endereco endereco) {
        return mapper.writeValueAsString(endereco);
    }
}
