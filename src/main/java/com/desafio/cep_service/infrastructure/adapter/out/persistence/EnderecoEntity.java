package com.desafio.cep_service.infrastructure.adapter.out.persistence;

import com.desafio.cep_service.domain.model.Cep;
import com.desafio.cep_service.domain.model.Endereco;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "enderecos", uniqueConstraints = @UniqueConstraint(name = "uk_enderecos_cep", columnNames = "cep"))
public class EnderecoEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 8, updatable = false)
    private String cep;
    @Column(columnDefinition = "text")
    private String logradouro;
    @Column(columnDefinition = "text")
    private String complemento;
    @Column(columnDefinition = "text")
    private String bairro;
    @Column(nullable = false)
    private String localidade;
    @Column(nullable = false, length = 2)
    private String uf;
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected EnderecoEntity() {}

    public EnderecoEntity(Endereco endereco) {
        cep = new Cep(endereco.cep().replace("-", "")).valor();
        logradouro = endereco.logradouro();
        complemento = endereco.complemento();
        bairro = endereco.bairro();
        localidade = endereco.localidade();
        uf = endereco.uf();
    }

    public Endereco toDomain() {
        String cepFormatado = cep.substring(0, 5) + "-" + cep.substring(5);
        return new Endereco(cepFormatado, logradouro, complemento, bairro, localidade, uf);
    }
}
