package com.desafio.cep_service.infrastructure.config;

import com.desafio.cep_service.application.port.out.ConsultaCepPort;
import com.desafio.cep_service.application.port.out.EnderecoRepositoryPort;
import com.desafio.cep_service.application.usecase.CepService;
import com.desafio.cep_service.application.usecase.CadastrarEnderecoService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

@Configuration(proxyBeanMethods = false)
public class CepConfiguration {
    @Bean
    CadastrarEnderecoService cadastrarEnderecoService(EnderecoRepositoryPort repository) {
        return new CadastrarEnderecoService(repository);
    }

    @Bean
    CepService cepService(ConsultaCepPort consultaCepPort, EnderecoRepositoryPort enderecoRepository) {
        return new CepService(consultaCepPort, enderecoRepository);
    }

    @Bean
    public RestClient viaCepRestClient(
            @Value("${viacep.base-url}") String baseUrl,
            @Value("${viacep.connect-timeout-ms}") int connectTimeoutMs,
            @Value("${viacep.read-timeout-ms}") int readTimeoutMs) {
        if (connectTimeoutMs <= 0 || readTimeoutMs <= 0) {
            throw new IllegalArgumentException("Os timeouts do ViaCEP devem ser positivos");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        // Aplicado apenas ao cliente externo, sem mudar a serialização pública REST.
        JsonMapper mapper = JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .withCoercionConfig(LogicalType.Textual, coercion -> coercion
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
                .build();
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .configureMessageConverters(converters -> converters.withJsonConverter(
                        new JacksonJsonHttpMessageConverter(mapper)))
                .build();
    }
}
