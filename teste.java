HttpStatusCode statusCode = response.getStatusCode();

        // RATE LIMIT: aplica a resposta (atualiza headers no sucesso, marca bloqueio no 429)
        rateLimiter.aplicarResposta(reserva, statusCode.value(), response.getHeaders());

        if (statusCode.is4xxClientError() || statusCode.is5xxServerError() || statusCode.isError()) {
            ...
