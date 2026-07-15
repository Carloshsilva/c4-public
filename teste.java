package br.com.b3.middlewares.selicconecta.outbound.exceptions;

/**
 * 429 vindo da SELIC (rate limit do provedor).
 *
 * Distingue o 429 dos demais erros de resposta da SELIC (que caem em
 * BadResponseFromSelicException). Quando ocorre, o rate limiter marca bloqueio
 * da identidade (via aplicarResposta) para proteger as próximas requisições.
 * Irmã de BadResponseFromSelicException na família SelicRelatedException.
 */
public class SelicRateLimitException extends SelicRelatedException {

    private static final long serialVersionUID = 1L;

    public SelicRateLimitException(int code, int httpStatus, String message) {
        super(code, httpStatus, message);
    }

    public SelicRateLimitException(int code, int httpStatus, String message, Throwable cause) {
        super(code, httpStatus, message, cause);
    }
}
