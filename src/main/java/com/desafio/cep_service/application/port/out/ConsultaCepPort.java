
package com.desafio.cep_service.application.port.out;

import com.desafio.cep_service.dto.CepResponse;

public interface ConsultaCepPort {

    CepResponse buscar(String cep);
}
