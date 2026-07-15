package br.com.b3.middlewares.selicconecta.outbound.services.ratelimit;

import java.io.Serializable;

/**
 * Estado de rate limit de uma identidade junto à SELIC. POJO de dados puro
 * (sem lógica de decisão, sem log). Usado em três papéis: o estado compartilhado
 * entre instâncias (irá para o cache distribuído) e a visão local de cada instância.
 * Por viajar no cache, implementa Serializable.
 *
 * Os "verbos" abaixo alteram SÓ este objeto — quem decide QUAL verbo chamar é o RateLimiter.
 */
public class Cota implements Serializable {

    private static final long serialVersionUID = 1L;

    public final String nome;

    public boolean dadosReais;   // já recebeu header real da SELIC?
    public int     limite;       // RateLimit-Limit
    public int     restante;     // RateLimit-Remaining
    public int     reset;        // segundos até a virada (RateLimit-Reset)
    public int     contador;     // requisições encaminhadas por esta identidade
    public boolean bloqueado;    // em bloqueio por 429?
    public long    fimBloqueio;  // instante (em segundos) em que o bloqueio acaba; 0 se livre

    /** Cold start: parte do valor documental da SELIC. */
    public Cota(String nome, int limiteDocumental) {
        this.nome        = nome;
        this.dadosReais  = false;
        this.limite      = limiteDocumental;
        this.restante    = limiteDocumental;
        this.reset       = 0;
        this.contador    = 0;
        this.bloqueado   = false;
        this.fimBloqueio = 0L;
    }

    /** Encaminhou uma requisição: sobe o contador. */
    public void incrementa() {
        contador++;
    }

    /** Aplica 2xx com cabeçalhos: SÓ limite/restante/reset. NÃO toca no contador. Usado nos dois modos. */
    public void atualizaComHeader(int novoLimite, int novoRem, int novoReset) {
        this.dadosReais = true;
        this.limite     = novoLimite;
        this.restante   = novoRem;
        this.reset      = novoReset;
    }

    /**
     * Corrige o contador conjunto pela verdade da SELIC (SÓ no modo coordenado).
     * Detecta virada de janela: rem SUBIU e reset REINICIOU. Chamar ANTES de atualizaComHeader
     * (lê restante/reset antigos).
     */
    public void renovaOuPreservaContador(int novoLimite, int novoRem, int novoReset) {
        boolean virouJanela = this.dadosReais && (novoRem > this.restante) && (novoReset > this.reset);
        if (virouJanela) {
            this.contador = novoLimite - novoRem;                          // janela nova: renova
        } else {
            this.contador = Math.max(this.contador, novoLimite - novoRem); // mesma janela: preserva
        }
    }

    /** Recebeu 429: entra em bloqueio até 'fim' (instante em segundos). */
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
