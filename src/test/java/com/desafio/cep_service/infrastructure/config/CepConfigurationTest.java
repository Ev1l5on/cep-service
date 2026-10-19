package com.desafio.cep_service.infrastructure.config;

import com.desafio.cep_service.application.exception.FalhaConsultaCepException;
import com.desafio.cep_service.infrastructure.adapter.out.viacep.ViaCepClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CepConfigurationTest {
    @ParameterizedTest
    @CsvSource({"0,100", "100,0", "-1,100", "100,-1"})
    void rejeitaTimeoutsQuePermitemEsperaIlimitada(int connectTimeout, int readTimeout) {
        assertThrows(IllegalArgumentException.class, () -> new CepConfiguration()
                .viaCepRestClient("http://localhost", connectTimeout, readTimeout));
    }

    @Test
    void timeoutDeLeituraConfiguradoFuncionaComServidorHttpLocalSemRetry() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newSingleThreadExecutor();
        AtomicInteger calls = new AtomicInteger();
        server.setExecutor(executor);
        server.createContext("/ws/01001000/json/", exchange -> {
            calls.incrementAndGet();
            try {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 100);
                exchange.getResponseBody().flush();
                Thread.sleep(1500);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            var client = new ViaCepClient(new CepConfiguration().viaCepRestClient(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/ws", 1000, 100));
            var exception = assertThrows(FalhaConsultaCepException.class, () -> client.buscar("01001000"));
            assertEquals(FalhaConsultaCepException.Motivo.TIMEOUT, exception.getMotivo());
            assertEquals(1, calls.get());
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
