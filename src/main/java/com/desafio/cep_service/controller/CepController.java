package com.desafio.cep_service.controller;

import com.desafio.cep_service.dto.CepResponse;
import com.desafio.cep_service.service.CepService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ceps")
public class CepController {

    private final CepService cepService;

    public CepController(CepService cepService) {
        this.cepService = cepService;
    }

    @GetMapping("/{cep}")
    public CepResponse buscar(@PathVariable String cep) {
        return cepService.buscar(cep);
    }
}