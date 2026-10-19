package com.desafio.cep_service.infrastructure.adapter.in.rest;

import com.desafio.cep_service.application.exception.FalhaConsultaCepException;
import com.desafio.cep_service.domain.exception.CepInvalidoException;
import com.desafio.cep_service.domain.exception.CepNaoEncontradoException;
import com.desafio.cep_service.infrastructure.adapter.in.rest.dto.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Instant;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(CepInvalidoException.class)
    ResponseEntity<Object> cepInvalido(CepInvalidoException ex, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(CepNaoEncontradoException.class)
    ResponseEntity<Object> cepNaoEncontrado(CepNaoEncontradoException ex, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(FalhaConsultaCepException.class)
    ResponseEntity<Object> falhaConsulta(FalhaConsultaCepException ex, HttpServletRequest request) {
        HttpStatus status = switch (ex.getMotivo()) {
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case INDISPONIBILIDADE -> HttpStatus.SERVICE_UNAVAILABLE;
            case RESPOSTA_INVALIDA -> HttpStatus.BAD_GATEWAY;
        };
        // Não registrar mensagens/stack traces de clientes HTTP: podem conter URL e corpo externos.
        log.warn("Falha na consulta CEP: path={} motivo={} diagnostico={} causa={}",
                request.getRequestURI(), ex.getMotivo(), ex.getDiagnostico(), causaRaiz(ex));
        return response(status, ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> erroInesperado(Exception ex, HttpServletRequest request) {
        log.error("Falha interna: path={} tipo={} causa={}",
                request.getRequestURI(), ex.getClass().getSimpleName(), causaRaiz(ex));
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno do servidor", request.getRequestURI());
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body,
            HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        String path = ((ServletWebRequest) request).getRequest().getRequestURI();
        HttpStatus status = HttpStatus.valueOf(statusCode.value());
        return new ResponseEntity<>(error(status, "Não foi possível processar a requisição", path),
                headers, status);
    }

    private ResponseEntity<Object> response(HttpStatus status, String message, String path) {
        return ResponseEntity.status(status).body(error(status, message, path));
    }

    private ApiError error(HttpStatus status, String message, String path) {
        return new ApiError(Instant.now(), status.value(), status.getReasonPhrase(), message, path);
    }

    private String causaRaiz(Throwable ex) {
        Throwable cause = ex;
        for (int depth = 0; depth < 10 && cause.getCause() != null; depth++) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName();
    }
}
