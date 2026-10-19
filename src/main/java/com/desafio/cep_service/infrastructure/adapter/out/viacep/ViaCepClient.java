package com.desafio.cep_service.infrastructure.adapter.out.viacep;

import com.desafio.cep_service.application.exception.FalhaConsultaCepException;
import com.desafio.cep_service.application.port.out.ConsultaCepPort;
import com.desafio.cep_service.domain.model.Endereco;
import com.desafio.cep_service.infrastructure.adapter.out.viacep.dto.ViaCepResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Optional;
import java.util.Set;

import static com.desafio.cep_service.application.exception.FalhaConsultaCepException.Motivo.*;

@Component
public class ViaCepClient implements ConsultaCepPort {
    private static final Set<String> UFS = Set.of("AC", "AL", "AP", "AM", "BA", "CE", "DF",
            "ES", "GO", "MA", "MT", "MS", "MG", "PA", "PB", "PR", "PE", "PI", "RJ",
            "RN", "RS", "RO", "RR", "SC", "SP", "SE", "TO");
    private final RestClient restClient;

    public ViaCepClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public Optional<Endereco> buscar(String cep) {
        ViaCepResponse response;
        try {
            response = restClient.get().uri("/{cep}/json/", cep)
                    .retrieve().body(ViaCepResponse.class);
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            var motivo = switch (status) {
                case 408, 504 -> TIMEOUT;
                case 429, 503 -> INDISPONIBILIDADE;
                default -> RESPOSTA_INVALIDA;
            };
            throw new FalhaConsultaCepException(motivo, "HTTP_" + status, ex);
        } catch (ResourceAccessException ex) {
            throw new FalhaConsultaCepException(ehTimeout(ex) ? TIMEOUT : INDISPONIBILIDADE,
                    "FALHA_TRANSPORTE", ex);
        } catch (RestClientException ex) {
            throw new FalhaConsultaCepException(ehTimeout(ex) ? TIMEOUT : RESPOSTA_INVALIDA,
                    "FALHA_LEITURA_RESPOSTA", ex);
        }

        if (response == null) {
            throw new FalhaConsultaCepException(RESPOSTA_INVALIDA, "CORPO_VAZIO", null);
        }
        if (Boolean.TRUE.equals(response.erro())) {
            return Optional.empty();
        }
        validarResposta(response, cep);
        return Optional.of(new Endereco(response.cep(), response.logradouro(),
                response.complemento(), response.bairro(), response.localidade(), response.uf()));
    }

    private void validarResposta(ViaCepResponse response, String cepSolicitado) {
        // Logradouro, bairro e complemento podem ser vazios em CEPs de localidades.
        if (response.cep() == null || !response.cep().matches("[0-9]{5}-?[0-9]{3}")
                || !response.cep().replace("-", "").equals(cepSolicitado)) {
            throw new FalhaConsultaCepException(RESPOSTA_INVALIDA, "CEP_INCONSISTENTE", null);
        }
        if (response.localidade() == null || response.localidade().isBlank()
                || response.uf() == null || !UFS.contains(response.uf())) {
            throw new FalhaConsultaCepException(RESPOSTA_INVALIDA, "LOCALIDADE_OU_UF_INVALIDA", null);
        }
    }

    private boolean ehTimeout(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }
}
