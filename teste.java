package br.com.b3.middlewares.selicconecta.outbound.services.ratelimit;

/**
 * Resultado da decisão do RateLimiter (Momento 1 — "reservar").
 * Diz se a requisição pode ser encaminhada à SELIC e em qual modo,
 * para que o Momento 2 (aplicar a resposta) seja coerente mesmo que
 * o estado do cluster mude no meio da chamada.
 *
 * - permitido:  pode encaminhar? (se false, o proxy devolve 429 ao cliente)
 * - coordenado: a decisão foi tomada no modo coordenado (cluster formado)?
 * - ancora:     é a requisição-âncora da reancoragem (1a após o link voltar)?
 *               A âncora é tratada como coordenada ao aplicar a resposta.
 *
 * Objeto imutável: criado por uma das fábricas estáticas e nunca alterado
 * (sem setters; campos final).
 */
public final class Reserva {

    private final boolean permitido;
    private final boolean coordenado;
    private final boolean ancora;

    private Reserva(boolean permitido, boolean coordenado, boolean ancora) {
        this.permitido = permitido;
        this.coordenado = coordenado;
        this.ancora = ancora;
    }

    /** Permitida, modo coordenado (cluster formado). */
    public static Reserva coordenado() {
        return new Reserva(true, true, false);
    }

    /** Permitida, modo descoordenado (sem cluster). */
    public static Reserva descoordenado() {
        return new Reserva(true, false, false);
    }

    /** Permitida como âncora da reancoragem (tratada como coordenada ao aplicar a resposta). */
    public static Reserva ancora() {
        return new Reserva(true, true, true);
    }

    /** Negada: não encaminha; o proxy responde 429 ao cliente. */
    public static Reserva negada() {
        return new Reserva(false, false, false);
    }

    public boolean isPermitido()  { return permitido; }
    public boolean isCoordenado() { return coordenado; }
    public boolean isAncora()     { return ancora; }
}
