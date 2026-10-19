package com.desafio.cep_service.infrastructure.adapter.out.viacep.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** Compatibilidade com o ViaCEP, que também envia o indicador como string. */
public class ViaCepErroDeserializer extends ValueDeserializer<Boolean> {
    @Override
    public Boolean deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() == JsonToken.VALUE_TRUE) {
            return true;
        }
        if (parser.currentToken() == JsonToken.VALUE_FALSE) {
            return false;
        }
        if (parser.currentToken() == JsonToken.VALUE_STRING) {
            if ("true".equals(parser.getString())) {
                return true;
            }
            if ("false".equals(parser.getString())) {
                return false;
            }
        }
        return context.reportInputMismatch(Boolean.class, "Indicador de erro incompatível");
    }
}
