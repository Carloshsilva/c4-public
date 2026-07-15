import org.springframework.http.HttpHeaders;

public void aplicarResposta(Reserva reserva, int status, HttpHeaders headers) {
        Integer limite   = header(headers, H_LIMIT);
        Integer restante = header(headers, H_REMAINING);
        Integer reset    = header(headers, H_RESET);
        Integer retryAft = header(headers, H_RETRY_AFTER);
        boolean temHeaders = (limite != null && restante != null && reset != null);

        try {
            if (reserva.isCoordenado()) {
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

private Integer header(HttpHeaders headers, String nome) {
        if (headers == null) {
            return null;
        }
        String valor = headers.getFirst(nome); // HttpHeaders é case-insensitive e retorna o 1o valor
        if (valor == null) {
            return null;
        }
        try {
            return Integer.parseInt(valor.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }



private HttpHeaders headers(int limite, int restante, int reset) {
        HttpHeaders h = new HttpHeaders();
        h.add("RateLimit-Limit", String.valueOf(limite));
        h.add("RateLimit-Remaining", String.valueOf(restante));
        h.add("RateLimit-Reset", String.valueOf(reset));
        return h;
    }

    private HttpHeaders headers429(int reset, int retryAfter) {
        HttpHeaders h = new HttpHeaders();
        h.add("RateLimit-Reset", String.valueOf(reset));
        h.add("Retry-After", String.valueOf(retryAfter));
        return h;
    }

import org.springframework.http.HttpHeaders;
