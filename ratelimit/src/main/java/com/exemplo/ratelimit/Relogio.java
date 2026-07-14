package com.exemplo.ratelimit;

/**
 * Fonte de tempo. A lógica de bloqueio só compara 'agora() >= fimBloqueio'.
 * Unidade: SEGUNDOS (tem que casar com RateLimit-Reset e Retry-After do provedor).
 * Interface para permitir relógio falso em teste.
 */
public interface Relogio {
    long agora();
}
