package com.exemplo.ratelimit;

import org.springframework.stereotype.Component;

/** Relógio real (tempo do sistema, em segundos). No teste, troque por um relógio falso. */
@Component
public class RelogioSistema implements Relogio {
    @Override
    public long agora() {
        return System.currentTimeMillis() / 1000L;
    }
}
