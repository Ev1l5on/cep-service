package com.desafio.cep_service.infrastructure.adapter.in.rest;

import com.desafio.cep_service.application.usecase.CadastrarEnderecoService;
import com.desafio.cep_service.infrastructure.adapter.in.rest.dto.EnderecoRequest;
import com.desafio.cep_service.infrastructure.adapter.in.rest.dto.EnderecoResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/enderecos")
public class EnderecoController {
    private final CadastrarEnderecoService service;

    public EnderecoController(CadastrarEnderecoService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<EnderecoResponse> cadastrar(@RequestBody EnderecoRequest request) {
        var endereco = service.cadastrar(request.toDomain());
        return ResponseEntity.created(URI.create("/api/ceps/" + request.cep()))
                .body(EnderecoResponse.from(endereco));
    }
}
