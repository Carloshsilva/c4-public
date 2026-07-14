package com.exemplo.ratelimit;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * O maestro da ordem canônica de rate limiting distribuído.
 *
 * Uso pelo seu serviço de encaminhamento (DEPOIS das regras de negócio, ABRAÇANDO a chamada HTTP):
 *
 *   Reserva r = rateLimiter.reservar();
 *   if (!r.permitido()) return 429_ao_cliente;
 *   try {
 *       resposta = <sua chamada HTTP ao provedor>;
 *       return resposta_ao_cliente;
 *   } finally {
 *       rateLimiter.aplicarResposta(r, status, headers);  // SEMPRE, mesmo em falha
 *   }
 *
 * DECISÕES DE DESENHO (todas discutidas e justificadas):
 *  - Comp = bloco único no IMap, protegido por IMap.lock (sem CP Subsystem: 2 nós).
 *  - Dois momentos de lock (reservar / aplicar) com o HTTP solto no meio (coberto pelo R).
 *  - comunicando = cluster.getMembers().size() >= N, lido na hora.
 *  - comm_anterior (comunicavaAntes) = flag local por nó, detecta a volta do link.
 *  - Reconciliação (b): a 1a requisição pós-volta vira ÂNCORA; concorrentes tomam 429
 *    até a âncora reancorar o Comp no rem FRESCO. Flag 'reancorando' por nó.
 *
 * ATENÇÃO: a LÓGICA (verbos da Cota, réguas de decisão) foi validada por 30 cenários no
 * simulador. A CAMADA DISTRIBUÍDA (IMap.lock, cluster, âncora) é NOVA e não foi validada —
 * teste com 2 instâncias reais sob carga concorrente. Ver ROTEIRO.md.
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private static final String MAP_NAME = "rate-limit";
    private static final String COMP_KEY = "comp";

    // Nomes dos cabeçalhos do provedor. AJUSTE se a SELIC CONECTA usar outros nomes.
    private static final String H_LIMIT       = "RateLimit-Limit";
    private static final String H_REMAINING   = "RateLimit-Remaining";
    private static final String H_RESET       = "RateLimit-Reset";
    private static final String H_RETRY_AFTER = "Retry-After";

    private final HazelcastInstance hz;
    private final Relogio relogio;

    // ---- constantes de config (application.properties) ----
    private final int    limiteDocumental;
    private final int    n;
    private final double margem;
    private final int    r;

    // ---- estado LOCAL do nó (NÃO vai pro IMap) ----
    private final Cota local;                                     // visão própria (usada no descoordenado)
    private volatile boolean comunicavaAntes;                     // detecta a borda (link voltou)
    private final AtomicBoolean reancorando = new AtomicBoolean(false);

    public RateLimiter(HazelcastInstance hz,
                       Relogio relogio,
                       @Value("${ratelimit.limite-documental:300}") int limiteDocumental,
                       @Value("${ratelimit.n:2}") int n,
                       @Value("${ratelimit.margem:0.9}") double margem,
                       @Value("${ratelimit.reserva:20}") int r) {
        this.hz = hz;
        this.relogio = relogio;
        this.limiteDocumental = limiteDocumental;
        this.n = n;
        this.margem = margem;
        this.r = r;
        this.local = new Cota("Local", limiteDocumental);
        this.comunicavaAntes = true; // nasce assumindo cluster saudável (não reancora à toa no boot)
    }

    // ======================================================================
    // MOMENTO 1 — reservar (antes de chamar a API)
    // ======================================================================
    public Reserva reservar() {
        boolean comunicando = comunicando();

        // Borda: o link acabou de voltar? -> a 1a requisição vira âncora.
        if (comunicando && !comunicavaAntes) {
            if (reancorando.compareAndSet(false, true)) {
                comunicavaAntes = true;
                log.info("[rate-limit] link voltou: esta requisicao e a ANCORA da reancoragem");
                return Reserva.ancora();       // encaminha SEM confiar no Comp velho
            }
            return Reserva.negada();           // outro ja e ancora -> 429 ao cliente
        }
        // Reancoragem em curso e nao sou a ancora -> 429 ao cliente
        if (reancorando.get()) {
            return Reserva.negada();
        }

        comunicavaAntes = comunicando;
        return comunicando ? reservarCoordenado() : reservarDescoordenado();
    }

    private Reserva reservarCoordenado() {
        IMap<String, Cota> mapa = hz.getMap(MAP_NAME);
        mapa.lock(COMP_KEY);
        try {
            Cota comp = mapa.get(COMP_KEY);
            if (comp == null) comp = new Cota("Comp", limiteDocumental);

            // desbloqueio passivo pelo relogio
            if (comp.bloqueado && relogio.agora() >= comp.fimBloqueio) {
                comp.desbloqueia();
            }
            if (comp.bloqueado) {
                mapa.put(COMP_KEY, comp);
                return Reserva.negada();
            }
            // regua coordenada: contador < L
            if (comp.contador < comp.limite) {
                comp.incrementa();
                mapa.put(COMP_KEY, comp);
                return Reserva.coordenado();
            }
            mapa.put(COMP_KEY, comp);
            return Reserva.negada();
        } finally {
            mapa.unlock(COMP_KEY);
        }
    }

    private Reserva reservarDescoordenado() {
        if (local.bloqueado && relogio.agora() >= local.fimBloqueio) {
            local.desbloqueia();
        }
        if (local.bloqueado) {
            return Reserva.negada();
        }
        boolean pode;
        if (local.dadosReais) {
            pode = local.restante > r;                                  // regua: rem > R
        } else {
            pode = local.contador < cotaLocalColdStart(local.limite);   // cold start
        }
        if (pode) {
            local.incrementa();
            return Reserva.descoordenado();
        }
        return Reserva.negada();
    }

    // ======================================================================
    // MOMENTO 2 — aplicar a resposta (depois de chamar a API)
    // SEMPRE chamar, mesmo em falha de HTTP (senao a reancoragem nunca libera).
    // ======================================================================
    public void aplicarResposta(Reserva reserva, int status, Map<String, String> headers) {
        Integer L        = header(headers, H_LIMIT);
        Integer rem      = header(headers, H_REMAINING);
        Integer reset    = header(headers, H_RESET);
        Integer retryAft = header(headers, H_RETRY_AFTER);
        boolean temHeaders = (L != null && rem != null && reset != null);

        try {
            if (reserva.coordenado()) {   // coordenado E ancora caem aqui
                aplicarCoordenado(status, L, rem, reset, retryAft, temHeaders);
            } else {
                aplicarDescoordenado(status, L, rem, reset, retryAft, temHeaders);
            }
        } finally {
            if (reserva.ancora()) {
                reancorando.set(false);   // fim da reancoragem: libera os demais
                log.info("[rate-limit] reancoragem concluida");
            }
        }
    }

    private void aplicarCoordenado(int status, Integer L, Integer rem, Integer reset,
                                   Integer retryAft, boolean temHeaders) {
        IMap<String, Cota> mapa = hz.getMap(MAP_NAME);
        mapa.lock(COMP_KEY);
        try {
            Cota comp = mapa.get(COMP_KEY);
            if (comp == null) comp = new Cota("Comp", limiteDocumental);

            if (status == 429) {
                comp.bloqueia(relogio.agora() + duracaoBloqueio(retryAft, reset));
            } else if (temHeaders) {
                comp.renovaOuPreservaContador(L, rem, reset); // ANTES do header (le rem/reset antigos)
                comp.atualizaComHeader(L, rem, reset);
            }
            mapa.put(COMP_KEY, comp);
            local.copiaDe(comp);   // propagacao: mantem a visao local pronta p/ eventual descoordenado
        } finally {
            mapa.unlock(COMP_KEY);
        }
    }

    private void aplicarDescoordenado(int status, Integer L, Integer rem, Integer reset,
                                      Integer retryAft, boolean temHeaders) {
        if (status == 429) {
            local.bloqueia(relogio.agora() + duracaoBloqueio(retryAft, reset));
        } else if (temHeaders) {
            local.atualizaComHeader(L, rem, reset);  // NAO mexe no contador local
        }
    }

    // ======================================================================
    // auxiliares
    // ======================================================================
    private boolean comunicando() {
        return hz.getCluster().getMembers().size() >= n;
    }

    private int cotaLocalColdStart(int limite) {
        return (int) Math.floor(limite * (1.0 / n) * margem);
    }

    private long duracaoBloqueio(Integer retryAfter, Integer reset) {
        if (retryAfter != null) return retryAfter;   // Retry-After tem precedencia
        if (reset != null)      return reset;
        return 0L;
    }

    private Integer header(Map<String, String> h, String nome) {
        if (h == null) return null;
        String v = h.get(nome);
        if (v == null) {
            for (Map.Entry<String, String> e : h.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(nome)) { v = e.getValue(); break; }
            }
        }
        if (v == null) return null;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException ex) { return null; }
    }
}
