package br.com.b3.middlewares.selicconecta.outbound.services.ratelimit;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;

/**
 * Núcleo do rate limiter de saída (chamadas à SELIC).
 *
 * Uso pelo serviço de encaminhamento (DEPOIS das regras de negócio, ABRAÇANDO a chamada HTTP):
 *   Reserva r = rateLimiter.reservar();
 *   if (!r.isPermitido()) -> responde 429 ao cliente;
 *   try { resposta = chamaSelic(); }
 *   finally { rateLimiter.aplicarResposta(r, status, headers); }  // SEMPRE, mesmo em falha
 *
 * Decisões de desenho (validadas em simulação de 30 cenários para a LÓGICA;
 * a camada distribuída — IMap.lock, cluster, âncora — precisa de teste com 2 instâncias):
 *  - Comp = bloco único no IMap, protegido por IMap.lock (sem CP Subsystem).
 *  - Dois momentos de lock (reservar / aplicar), com o HTTP FORA do lock (coberto pelo R).
 *  - comunicando = getMembers().size() >= N, lido na hora.
 *  - comunicavaAntes = flag local por nó, detecta a volta do cluster.
 *  - Reancoragem: a 1a requisição pós-volta vira âncora; concorrentes tomam 429
 *    até a âncora reancorar o Comp no 'restante' fresco da SELIC.
 */
@Component
public class RateLimiter {

    private static final Logger logger = LoggerFactory.getLogger(RateLimiter.class);

    private static final String MAP_NAME = "rate-limit";
    private static final String COMP_KEY = "comp";

    // Cabeçalhos de rate limit da SELIC (confirmados no diagnóstico).
    private static final String H_LIMIT       = "RateLimit-Limit";
    private static final String H_REMAINING   = "RateLimit-Remaining";
    private static final String H_RESET       = "RateLimit-Reset";
    private static final String H_RETRY_AFTER = "Retry-After";

    private final HazelcastInstance hazelcast;
    private final Clock clock;

    private final int    limiteDocumental;
    private final int    n;
    private final double margem;
    private final int    reserva;

    // Estado LOCAL do nó (não vai para o IMap).
    private final Cota local;
    private volatile boolean comunicavaAntes;
    private final AtomicBoolean reancorando = new AtomicBoolean(false);

    public RateLimiter(HazelcastInstance hazelcast,
                       Clock clock,
                       @Value("${ratelimit.limite-documental:300}") int limiteDocumental,
                       @Value("${ratelimit.n:2}") int n,
                       @Value("${ratelimit.margem:0.9}") double margem,
                       @Value("${ratelimit.reserva:20}") int reserva) {
        this.hazelcast = hazelcast;
        this.clock = clock;
        this.limiteDocumental = limiteDocumental;
        this.n = n;
        this.margem = margem;
        this.reserva = reserva;
        this.local = new Cota("Local", limiteDocumental);
        this.comunicavaAntes = true; // nasce assumindo cluster saudável (não reancora à toa no boot)
    }

    // ================= MOMENTO 1 — reservar (antes de chamar a SELIC) =================
    public Reserva reservar() {
        boolean comunicando = comunicando();

        // Borda: o cluster acabou de voltar? -> a 1a requisicao vira ancora.
        if (comunicando && !comunicavaAntes) {
            if (reancorando.compareAndSet(false, true)) {
                comunicavaAntes = true;
                logger.info("Cluster voltou: requisicao tratada como ancora da reancoragem");
                return Reserva.ancora();
            }
            return Reserva.negada(); // outra ja e ancora -> 429 ao cliente
        }
        if (reancorando.get()) {
            return Reserva.negada(); // reancoragem em curso, nao sou a ancora -> 429
        }

        comunicavaAntes = comunicando;
        return comunicando ? reservarCoordenado() : reservarDescoordenado();
    }

    private Reserva reservarCoordenado() {
        IMap<String, Cota> mapa = hazelcast.getMap(MAP_NAME);
        mapa.lock(COMP_KEY);
        try {
            Cota comp = mapa.get(COMP_KEY);
            if (comp == null) {
                comp = new Cota("Comp", limiteDocumental);
            }
            if (comp.bloqueado && agora() >= comp.fimBloqueio) {
                comp.desbloqueia();
            }
            if (comp.bloqueado) {
                mapa.put(COMP_KEY, comp);
                return Reserva.negada();
            }
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
        if (local.bloqueado && agora() >= local.fimBloqueio) {
            local.desbloqueia();
        }
        if (local.bloqueado) {
            return Reserva.negada();
        }
        boolean pode;
        if (local.dadosReais) {
            pode = local.restante > reserva;                 // regua: restante > R
        } else {
            pode = local.contador < cotaLocalColdStart(local.limite); // cold start
        }
        if (pode) {
            local.incrementa();
            return Reserva.descoordenado();
        }
        return Reserva.negada();
    }

    // ================= MOMENTO 2 — aplicar a resposta (depois da SELIC) =================
    // SEMPRE chamar, mesmo em falha de HTTP (senao a reancoragem nunca libera).
    public void aplicarResposta(Reserva reserva, int status, Map<String, String> headers) {
        Integer limite   = header(headers, H_LIMIT);
        Integer restante = header(headers, H_REMAINING);
        Integer reset    = header(headers, H_RESET);
        Integer retryAft = header(headers, H_RETRY_AFTER);
        boolean temHeaders = (limite != null && restante != null && reset != null);

        try {
            if (reserva.isCoordenado()) { // coordenado E ancora caem aqui
                aplicarCoordenado(status, limite, restante, reset, retryAft, temHeaders);
            } else {
                aplicarDescoordenado(status, limite, restante, reset, retryAft, temHeaders);
            }
        } finally {
            if (reserva.isAncora()) {
                reancorando.set(false);
                logger.info("Reancoragem concluida");
            }
        }
    }

    private void aplicarCoordenado(int status, Integer limite, Integer restante, Integer reset,
                                   Integer retryAft, boolean temHeaders) {
        IMap<String, Cota> mapa = hazelcast.getMap(MAP_NAME);
        mapa.lock(COMP_KEY);
        try {
            Cota comp = mapa.get(COMP_KEY);
            if (comp == null) {
                comp = new Cota("Comp", limiteDocumental);
            }
            if (status == 429) {
                comp.bloqueia(agora() + duracaoBloqueio(retryAft, reset));
            } else if (temHeaders) {
                comp.renovaOuPreservaContador(limite, restante, reset);
                comp.atualizaComHeader(limite, restante, reset);
            }
            mapa.put(COMP_KEY, comp);
            local.copiaDe(comp); // mantem a visao local pronta para eventual descoordenado
        } finally {
            mapa.unlock(COMP_KEY);
        }
    }

    private void aplicarDescoordenado(int status, Integer limite, Integer restante, Integer reset,
                                      Integer retryAft, boolean temHeaders) {
        if (status == 429) {
            local.bloqueia(agora() + duracaoBloqueio(retryAft, reset));
        } else if (temHeaders) {
            local.atualizaComHeader(limite, restante, reset); // NAO mexe no contador local
        }
    }

    // ================= auxiliares =================
    private boolean comunicando() {
        return hazelcast.getCluster().getMembers().size() >= n;
    }

    private long agora() {
        return clock.instant().getEpochSecond();
    }

    private int cotaLocalColdStart(int limite) {
        return (int) Math.floor(limite * (1.0 / n) * margem);
    }

    private long duracaoBloqueio(Integer retryAfter, Integer reset) {
        if (retryAfter != null) {
            return retryAfter; // Retry-After tem precedencia
        }
        if (reset != null) {
            return reset;
        }
        return 0L;
    }

    private Integer header(Map<String, String> headers, String nome) {
        if (headers == null) {
            return null;
        }
        String valor = headers.get(nome);
        if (valor == null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(nome)) {
                    valor = e.getValue();
                    break;
                }
            }
        }
        if (valor == null) {
            return null;
        }
        try {
            return Integer.parseInt(valor.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
