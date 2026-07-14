package com.exemplo.ratelimit;

import java.io.Serializable;

/**
 * Estado de rate limit. É um POJO de DADOS puro (sem lógica de decisão, sem log).
 * Usado em três papéis: o "Comp" (compartilhado, mora no IMap do Hazelcast) e a
 * visão local de cada nó. Por viajar no IMap, precisa ser Serializable.
 *
 * As "verbos" abaixo alteram SÓ este objeto — quem decide QUAL verbo chamar é o RateLimiter.
 * Toda a lógica destes métodos foi validada no simulador (30 cenários).
 */
public class Cota implements Serializable {

    private static final long serialVersionUID = 1L;

    public final String nome;

    public boolean dadosReais;   // já recebeu header real do provedor?
    public int     limite;       // L (RateLimit-Limit)
    public int     restante;     // rem (RateLimit-Remaining)
    public int     reset;        // seg até a virada (RateLimit-Reset)
    public int     contador;     // requisições encaminhadas
    public boolean bloqueado;    // identidade em 429?
    public long    fimBloqueio;  // instante (do Relogio, em segundos) em que o bloqueio acaba

    /** Cold start: parte do valor documental do provedor. */
    public Cota(String nome, int limiteDocumental) {
        this.nome        = nome;
        this.dadosReais  = false;
        this.limite      = limiteDocumental;
        this.restante    = limiteDocumental; // = limite - contador (contador=0)
        this.reset       = 0;
        this.contador    = 0;
        this.bloqueado   = false;
        this.fimBloqueio = 0L;
    }

    /** Encaminhou uma requisição: sobe o contador. */
    public void incrementa() {
        contador++;
    }

    /** Aplica 2xx com cabeçalhos: SÓ L/rem/reset. NÃO toca no contador. Usado nos dois modos. */
    public void atualizaComHeader(int novoLimite, int novoRem, int novoReset) {
        this.dadosReais = true;
        this.limite     = novoLimite;
        this.restante   = novoRem;
        this.reset      = novoReset;
    }

    /**
     * Corrige o contador conjunto pelo provedor (SÓ coordenado). Chamar ANTES de atualizaComHeader
     * (lê o rem/reset ANTIGOS para detectar a virada de janela).
     * Regra (c): virada = rem subiu E reset reiniciou. Virou -> renova; senão -> preserva o consumo.
     */
    public void renovaOuPreservaContador(int novoLimite, int novoRem, int novoReset) {
        boolean virouJanela = this.dadosReais && (novoRem > this.restante) && (novoReset > this.reset);
        if (virouJanela) {
            this.contador = novoLimite - novoRem;                          // janela nova
        } else {
            this.contador = Math.max(this.contador, novoLimite - novoRem); // mesma janela: preserva
        }
    }

    /** Recebeu 429: entra em bloqueio até 'fim' (instante do Relogio). */
    public void bloqueia(long fim) {
        this.bloqueado   = true;
        this.fimBloqueio = fim;
    }

    /** O tempo passou: sai do bloqueio e a janela renova (zera contador). */
    public void desbloqueia() {
        this.bloqueado   = false;
        this.fimBloqueio = 0L;
        this.contador    = 0;
    }

    /** Propagação: copia o estado compartilhável de outra Cota (NÃO copia o contador local). */
    public void copiaDe(Cota fonte) {
        this.dadosReais  = fonte.dadosReais;
        this.limite      = fonte.limite;
        this.restante    = fonte.restante;
        this.reset       = fonte.reset;
        this.bloqueado   = fonte.bloqueado;
        this.fimBloqueio = fonte.fimBloqueio;
    }

    @Override
    public String toString() {
        return String.format("%s{real=%s L=%d rem=%d reset=%d cont=%d blq=%s fimBlq=%d}",
                nome, dadosReais, limite, restante, reset, contador, bloqueado, fimBloqueio);
    }
}
