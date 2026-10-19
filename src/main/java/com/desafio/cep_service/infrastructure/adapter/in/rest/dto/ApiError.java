package com.desafio.cep_service.infrastructure.adapter.in.rest.dto;

import java.time.Instant;

public record ApiError(Instant timestamp, int status, String error, String message, String path) {}
