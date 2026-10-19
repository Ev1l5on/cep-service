package com.desafio.cep_service.infrastructure.adapter.out.persistence;

import com.desafio.cep_service.application.exception.EnderecoJaCadastradoException;
import com.desafio.cep_service.application.exception.FalhaPersistenciaEnderecoException;
import com.desafio.cep_service.application.port.out.ConsultaCepPort;
import com.desafio.cep_service.application.port.out.EnderecoRepositoryPort;
import com.desafio.cep_service.application.usecase.CepService;
import com.desafio.cep_service.application.usecase.CadastrarEnderecoService;
import com.desafio.cep_service.domain.model.Endereco;
import com.desafio.cep_service.infrastructure.adapter.in.rest.ApiExceptionHandler;
import com.desafio.cep_service.infrastructure.adapter.in.rest.CepController;
import com.desafio.cep_service.infrastructure.adapter.in.rest.EnderecoController;
import com.desafio.cep_service.infrastructure.adapter.in.rest.dto.EnderecoRequest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
class EnderecoPersistenceIntegrationTest {
    // Schema novo por execução: nenhum registro/tabela preexistente é apagado pelos testes.
    private static final String SCHEMA = "cep_it_" + UUID.randomUUID().toString().replace("-", "");

    @DynamicPropertySource
    static void schemaIsolado(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        registry.add("spring.datasource.hikari.schema", () -> SCHEMA);
    }

    @Autowired EnderecoPersistenceAdapter adapter;
    @Autowired EnderecoJpaRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired CadastrarEnderecoService cadastro;

    @Test
    void migracaoReproduzivelAplicadaAntesDaValidacaoJpa() {
        assertEquals("1", flyway.info().current().getVersion().getVersion());
        flyway.validate();
        assertEquals(0, flyway.migrate().migrationsExecuted);
        assertEquals(SCHEMA, jdbc.queryForObject("SELECT current_schema()", String.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = ? AND success", Integer.class, "1"));
    }

    @Test
    void gravaERecuperaEnderecoComCepCanonicoETimestamps() {
        String cep = "11111000";
        assertTrue(adapter.buscarPorCep(cep).isEmpty());
        Endereco expected = endereco(cep);
        assertEquals(expected, adapter.salvar(expected));
        assertEquals(expected, adapter.buscarPorCep(cep).orElseThrow());
        assertEquals(cep, jdbc.queryForObject("SELECT cep FROM enderecos WHERE cep = ?", String.class, cep));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM enderecos WHERE cep = ? "
                + "AND id IS NOT NULL AND created_at IS NOT NULL AND updated_at IS NOT NULL", Integer.class, cep));
    }

    @Test
    void primeiraConsultaPersisteESegundaNaoAcionaViaCepSemTransacaoDuranteHttp() throws Exception {
        String cep = "22222000";
        ConsultaCepPort externo = mock(ConsultaCepPort.class);
        when(externo.buscar(cep)).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return Optional.of(endereco(cep));
        });
        CepService service = new CepService(externo, adapter);
        var mvc = MockMvcBuilders.standaloneSetup(new CepController(service))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        for (int i = 0; i < 2; i++) {
            mvc.perform(get("/api/ceps/" + cep)).andExpect(status().isOk())
                    .andExpect(content().json("""
                            {"cep":"22222-000","logradouro":"Rua de teste","complemento":"",
                             "bairro":"Centro","localidade":"São Paulo","uf":"SP","erro":null}
                            """, org.springframework.test.json.JsonCompareMode.STRICT));
        }
        verify(externo, times(1)).buscar(cep);
        assertEquals(1, quantidade(cep));
    }

    @Test
    void conflitoRealFazRollbackENovaTransacaoConsegueLer() {
        String cep = "33333000";
        adapter.salvar(endereco(cep));
        assertThrows(EnderecoJaCadastradoException.class, () -> adapter.salvar(endereco(cep)));
        assertEquals(endereco(cep), adapter.buscarPorCep(cep).orElseThrow());
        assertEquals(1, quantidade(cep));
    }

    @Test
    void duasRequisicoesConcorrentesRetornamVencedorSemDuplicarRegistro() throws Exception {
        String cep = "44444000";
        var barrier = new CyclicBarrier(2);
        var calls = new AtomicInteger();
        ConsultaCepPort externo = mock(ConsultaCepPort.class);
        EnderecoRepositoryPort persistence = spy(adapter);
        when(externo.buscar(cep)).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            int number = calls.incrementAndGet();
            barrier.await(10, TimeUnit.SECONDS);
            Endereco base = endereco(cep);
            return Optional.of(new Endereco(base.cep(), "Rua concorrente " + number,
                    base.complemento(), base.bairro(), base.localidade(), base.uf()));
        });
        CepService service = new CepService(externo, persistence);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.buscar(cep));
            var second = executor.submit(() -> service.buscar(cep));
            Endereco a = first.get(20, TimeUnit.SECONDS);
            Endereco b = second.get(20, TimeUnit.SECONDS);
            assertEquals(a, b);
            assertEquals(a, adapter.buscarPorCep(cep).orElseThrow());
        }
        assertEquals(1, quantidade(cep));
        verify(externo, times(2)).buscar(cep);
        // Duas leituras iniciais e uma recuperação após rollback da gravação concorrente.
        verify(persistence, times(3)).buscarPorCep(cep);
    }

    @Test
    void outraViolacaoDeIntegridadeNaoEhClassificadaComoCepExistente() {
        var invalid = new Endereco("55555-000", "Rua", "", "", null, "SP");
        var ex = assertThrows(FalhaPersistenciaEnderecoException.class, () -> adapter.salvar(invalid));
        assertEquals(FalhaPersistenciaEnderecoException.Operacao.GRAVACAO, ex.getOperacao());
        assertEquals(FalhaPersistenciaEnderecoException.Motivo.ERRO, ex.getMotivo());
        assertEquals(0, quantidade("55555000"));
    }

    @Test
    void consultaDerivadaUsaParametroSemInterpretarSqlDaEntrada() {
        String cep = "66666000";
        adapter.salvar(endereco(cep));
        assertTrue(repository.findByCep("' OR '1'='1").isEmpty());
        assertTrue(repository.findByCep(cep).isPresent());
        assertEquals(1, quantidade(cep));
    }

    private int quantidade(String cep) {
        return jdbc.queryForObject("SELECT count(*) FROM enderecos WHERE cep = ?", Integer.class, cep);
    }

    @Test
    void cadastroManualPeloPostEhPriorizadoNoGetSemViaCep() throws Exception {
        String cep = "77777000";
        ConsultaCepPort externo = mock(ConsultaCepPort.class);
        MockMvc mvc = mvcCadastro(externo);
        String request = cadastroJson(cep, "Rua cadastrada manualmente");
        mvc.perform(post("/api/enderecos").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/ceps/" + cep))
                .andExpect(content().json(request.replace(cep, "77777-000"),
                        org.springframework.test.json.JsonCompareMode.STRICT));
        mvc.perform(get("/api/ceps/" + cep)).andExpect(status().isOk())
                .andExpect(content().json("""
                        {"cep":"77777-000","logradouro":"Rua cadastrada manualmente","complemento":"Manual",
                         "bairro":"Centro","localidade":"São Paulo","uf":"SP","erro":null}
                        """, org.springframework.test.json.JsonCompareMode.STRICT));
        verifyNoInteractions(externo);
        assertEquals(1, quantidade(cep));
    }

    @Test
    void cadastroDuplicadoRetorna409EPreservaEnderecoOriginal() throws Exception {
        String cep = "88888000";
        ConsultaCepPort externo = mock(ConsultaCepPort.class);
        MockMvc mvc = mvcCadastro(externo);
        mvc.perform(post("/api/enderecos").contentType(MediaType.APPLICATION_JSON)
                .content(cadastroJson(cep, "Rua original"))).andExpect(status().isCreated());
        mvc.perform(post("/api/enderecos").contentType(MediaType.APPLICATION_JSON)
                .content(cadastroJson(cep, "Tentativa de sobrescrita")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("Já existe um endereço cadastrado para o CEP informado"));
        assertEquals("Rua original", adapter.buscarPorCep(cep).orElseThrow().logradouro());
        assertEquals(1, quantidade(cep));
        verifyNoInteractions(externo);
    }

    @Test
    void doisPostsConcorrentesRetornam201E409SemDuplicar() throws Exception {
        String cep = "99999000";
        ConsultaCepPort externo = mock(ConsultaCepPort.class);
        MockMvc mvc = mvcCadastro(externo);
        var barrier = new CyclicBarrier(2);
        java.util.concurrent.Callable<Integer> request = () -> {
            barrier.await(10, TimeUnit.SECONDS);
            return mvc.perform(post("/api/enderecos").contentType(MediaType.APPLICATION_JSON)
                            .content(cadastroJson(cep, "Rua concorrente manual")))
                    .andReturn().getResponse().getStatus();
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(request);
            var second = executor.submit(request);
            var statuses = java.util.Set.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertEquals(java.util.Set.of(201, 409), statuses);
        }
        assertEquals(1, quantidade(cep));
        verifyNoInteractions(externo);
    }

    @Test
    void cadastroManualDuranteConsultaExternaVenceEGetRecuperaRegistroManual() throws Exception {
        String cep = "77778000";
        var iniciouHttp = new CountDownLatch(1);
        var concluirHttp = new CountDownLatch(1);
        ConsultaCepPort externo = mock(ConsultaCepPort.class);
        when(externo.buscar(cep)).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            iniciouHttp.countDown();
            assertTrue(concluirHttp.await(10, TimeUnit.SECONDS));
            return Optional.of(endereco(cep));
        });
        var executor = Executors.newSingleThreadExecutor();
        try {
            var consulta = executor.submit(() -> new CepService(externo, adapter).buscar(cep));
            assertTrue(iniciouHttp.await(10, TimeUnit.SECONDS));
            Endereco manual = cadastro.cadastrar(new Endereco(cep, "Rua manual prioritária",
                    "Manual", "Centro", "São Paulo", "SP"));
            concluirHttp.countDown();
            assertEquals(manual, consulta.get(20, TimeUnit.SECONDS));
            assertEquals(1, quantidade(cep));
            verify(externo, times(1)).buscar(cep);
        } finally {
            concluirHttp.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void cadastroComApostrofosEhPersistidoComoDadoSemExecutarSql() throws Exception {
        String cep = "78787000";
        String logradouro = "Rua D'Ávila ' OR '1'='1";
        var externo = mock(ConsultaCepPort.class);
        mvcCadastro(externo).perform(post("/api/enderecos").contentType(MediaType.APPLICATION_JSON)
                .content(cadastroJson(cep, logradouro))).andExpect(status().isCreated());
        assertEquals(logradouro, adapter.buscarPorCep(cep).orElseThrow().logradouro());
        assertEquals(1, quantidade(cep));
        verifyNoInteractions(externo);
    }

    private MockMvc mvcCadastro(ConsultaCepPort externo) {
        return MockMvcBuilders.standaloneSetup(new EnderecoController(cadastro),
                        new CepController(new CepService(externo, adapter)))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private String cadastroJson(String cep, String logradouro) {
        return JsonMapper.builder().build().writeValueAsString(
                new EnderecoRequest(cep, logradouro, "Manual", "Centro", "São Paulo", "SP"));
    }

    private Endereco endereco(String cep) {
        return new Endereco(cep.substring(0, 5) + "-" + cep.substring(5),
                "Rua de teste", "", "Centro", "São Paulo", "SP");
    }
}
