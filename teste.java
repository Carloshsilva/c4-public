package br.com.b3.middlewares.selicconecta.outbound.services.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.hazelcast.cluster.Cluster;
import com.hazelcast.cluster.Member;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;

/**
 * Testes do RateLimiter. Cobre uma vez cada caminho de decisao:
 * coordenado (encaminha/recusa/429/desbloqueio), descoordenado (rem>R e cold start)
 * e reancoragem. As variacoes finas do contador (renova vs preserva) ja estao no CotaTest.
 *
 * Padrao do projeto: @Mock + MockitoAnnotations.openMocks(this) em @BeforeEach, doReturn().when().
 * O IMap mockado delega get/put para um HashMap real, para o estado do Comp persistir entre chamadas;
 * lock/unlock sao no-ops (o teste roda numa thread so).
 */
public class RateLimiterTest {

    private static final int LIMITE_DOCUMENTAL = 300;
    private static final int N = 2;
    private static final double MARGEM = 0.9;
    private static final int RESERVA = 20;

    @Mock private HazelcastInstance hazelcast;
    @Mock private Cluster cluster;
    @Mock private IMap<String, Cota> mapa;
    private final Map<String, Cota> mapaReal = new HashMap<>();

    private long agoraSeg;
    private Clock clock;

    private RateLimiter rateLimiter;

    @BeforeEach
    public void init() throws Exception {
        MockitoAnnotations.openMocks(this);
        mapaReal.clear();
        agoraSeg = 0L;

        // Clock que le a variavel agoraSeg (permite "avancar o tempo" nos testes)
        clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.ofEpochSecond(agoraSeg); }
        };

        doReturn(mapa).when(hazelcast).getMap(anyString());

        doAnswer(inv -> mapaReal.get(inv.getArgument(0))).when(mapa).get(anyString());
        doAnswer(inv -> mapaReal.put(inv.getArgument(0), inv.getArgument(1)))
                .when(mapa).put(anyString(), org.mockito.ArgumentMatchers.any(Cota.class));
        doAnswer(inv -> null).when(mapa).lock(anyString());
        doAnswer(inv -> null).when(mapa).unlock(anyString());

        doReturn(cluster).when(hazelcast).getCluster();

        rateLimiter = new RateLimiter(hazelcast, clock, LIMITE_DOCUMENTAL, N, MARGEM, RESERVA);
    }

    private void setMembros(int quantidade) {
        java.util.Set<Member> membros = new java.util.HashSet<>();
        for (int i = 0; i < quantidade; i++) {
            membros.add(org.mockito.Mockito.mock(Member.class));
        }
        doReturn(membros).when(cluster).getMembers();
    }

    private Cota comp() {
        return mapaReal.get("comp");
    }

    private Map<String, String> headers(int limite, int restante, int reset) {
        Map<String, String> h = new HashMap<>();
        h.put("RateLimit-Limit", String.valueOf(limite));
        h.put("RateLimit-Remaining", String.valueOf(restante));
        h.put("RateLimit-Reset", String.valueOf(reset));
        return h;
    }

    private Map<String, String> headers429(int reset, int retryAfter) {
        Map<String, String> h = new HashMap<>();
        h.put("RateLimit-Reset", String.valueOf(reset));
        h.put("Retry-After", String.valueOf(retryAfter));
        return h;
    }

    // ==================== 1. coordenado encaminha ====================
    @Test
    public void testCoordenadoEncaminhaEIncrementaOComp() {
        setMembros(2);

        Reserva r = rateLimiter.reservar();

        assertTrue(r.isPermitido());
        assertTrue(r.isCoordenado());
        assertEquals(1, comp().contador);
    }

    // ==================== 2. coordenado recusa no limite ====================
    @Test
    public void testCoordenadoRecusaQuandoNoLimite() {
        setMembros(2);
        Cota comp = new Cota("Comp", LIMITE_DOCUMENTAL);
        comp.contador = comp.limite;
        mapaReal.put("comp", comp);

        Reserva r = rateLimiter.reservar();

        assertFalse(r.isPermitido());
        assertEquals(comp.limite, comp().contador);
    }

    // ==================== 3. 429 bloqueia com Retry-After ====================
    @Test
    public void testCoordenado429BloqueiaComRetryAfter() {
        setMembros(2);
        rateLimiter.reservar();

        rateLimiter.aplicarResposta(Reserva.coordenado(), 429, headers429(30, 5));

        assertTrue(comp().bloqueado);
        assertEquals(5L, comp().fimBloqueio);
    }

    // ==================== 4. desbloqueio pelo relogio ====================
    @Test
    public void testDesbloqueioQuandoRelogioPassaOFim() {
        setMembros(2);
        Cota comp = new Cota("Comp", LIMITE_DOCUMENTAL);
        comp.bloqueia(5L);
        mapaReal.put("comp", comp);

        agoraSeg = 3L;
        assertFalse(rateLimiter.reservar().isPermitido());
        assertTrue(comp().bloqueado);

        agoraSeg = 5L;
        Reserva r = rateLimiter.reservar();
        assertTrue(r.isPermitido());
        assertFalse(comp().bloqueado);
    }

    // ==================== 5. descoordenado: rem > R ====================
    @Test
    public void testDescoordenadoDecidePorRestanteMaiorQueReserva() {
        setMembros(1);

        rateLimiter.reservar();
        rateLimiter.aplicarResposta(Reserva.descoordenado(), 200, headers(300, 100, 60));

        Reserva r = rateLimiter.reservar();
        assertTrue(r.isPermitido());
        assertFalse(r.isCoordenado());
    }

    @Test
    public void testDescoordenadoRecusaQuandoRestanteNaReserva() {
        setMembros(1);
        rateLimiter.reservar();
        rateLimiter.aplicarResposta(Reserva.descoordenado(), 200, headers(300, 20, 60));

        Reserva r = rateLimiter.reservar();
        assertFalse(r.isPermitido());
    }

    // ==================== 6. cold start descoordenado ====================
    @Test
    public void testColdStartDescoordenadoUsaCotaLocal() {
        setMembros(1);

        Reserva r = rateLimiter.reservar();
        assertTrue(r.isPermitido());
        assertFalse(r.isCoordenado());
    }

    // ==================== 7. reancoragem na volta do cluster ====================
    @Test
    public void testReancoragemAncoraLiberaEConcorrenteRecusa() {
        setMembros(1);
        rateLimiter.reservar();

        setMembros(2);
        Reserva ancora = rateLimiter.reservar();
        assertTrue(ancora.isPermitido());
        assertTrue(ancora.isAncora());

        Reserva concorrente = rateLimiter.reservar();
        assertFalse(concorrente.isPermitido());

        rateLimiter.aplicarResposta(ancora, 200, headers(300, 250, 60));

        Reserva depois = rateLimiter.reservar();
        assertTrue(depois.isPermitido());
    }
}
