package br.com.b3.middlewares.selicconecta.outbound.exceptions;

/**
 * Recusa interna por rate limit de saída (antes de chamar a SELIC).
 * Lançada quando o rate limiter não permite encaminhar (cota no limite ou
 * bloqueio ativo). NÃO é 429 da SELIC: recusa preventiva, mesma natureza das
 * validações de entrada; cliente recebe 429 para retentar. Não causa bloqueio.
 * Faixa de código 18xx (rate limiter interno).
 */
public class RateLimitExceededException extends ProcessingException {

    private static final long serialVersionUID = 1L;

    public RateLimitExceededException(int code, String message) {
        super(code, message);
    }
}
