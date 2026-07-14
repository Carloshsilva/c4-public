package com.exemplo.ratelimit;

/**
 * Resultado do Momento 1 (reservar). Carrega o MODO decidido na reserva, para que o
 * Momento 2 (aplicarResposta) seja coerente mesmo que o estado do cluster mude no meio.
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

    public static Reserva coordenado()    { return new Reserva(true,  true,  false); }
    public static Reserva descoordenado() { return new Reserva(true,  false, false); }
    public static Reserva ancora()        { return new Reserva(true,  true,  true);  } // reancoragem: trata como coordenado no apply
    public static Reserva negada()        { return new Reserva(false, false, false); }

    public boolean permitido()  { return permitido; }
    public boolean coordenado() { return coordenado; }
    public boolean ancora()     { return ancora; }
}
