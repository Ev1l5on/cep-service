package com.desafio.cep_service.infrastructure.config;

import com.desafio.cep_service.application.port.out.ConsultaCepPort;
import com.desafio.cep_service.application.usecase.CepService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class CepConfiguration {
    @Bean
    CepService cepService(ConsultaCepPort consultaCepPort) {
        return new CepService(consultaCepPort);
    }

    @Bean
    RestClient viaCepRestClient(
            @Value("${viacep.base-url:https://viacep.com.br/ws}") String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl).build();
    }
}
