package br.com.b3.middlewares.selicconecta.outbound.configs;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuração do rate limiter de saída (chamadas à SELIC).
 * Por ora, expõe o Clock usado para medir o tempo de bloqueio (em segundos).
 */
@Configuration
public class RateLimitConfig {

    /** Relógio do sistema (UTC). Em teste, injeta-se um Clock.fixed(...). */
    @Bean
    public Clock rateLimitClock() {
        return Clock.systemUTC();
    }
}
