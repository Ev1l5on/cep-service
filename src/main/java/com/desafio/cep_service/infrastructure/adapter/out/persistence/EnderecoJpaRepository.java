package com.desafio.cep_service.infrastructure.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EnderecoJpaRepository extends JpaRepository<EnderecoEntity, Long> {
    Optional<EnderecoEntity> findByCep(String cep);
}
