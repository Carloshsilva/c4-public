public String sendMessageToSelic(String message, String endpoint, String token)
        throws HaltedCommunicationException, BadResponseFromSelicException, RateLimitExceededException {

    logger.info("Iniciando envio de requisicao para a SELIC");

    // RATE LIMIT (saida): reserva a vaga antes de chamar a SELIC
    Reserva reserva = rateLimiter.reservar();
    if (!reserva.isPermitido()) {
        logger.warn("Rate limit interno: requisicao recusada antes de enviar a SELIC");
        throw new RateLimitExceededException(1801, "Rate limit interno: requisicao recusada");
    }

    HttpHeaders headers = generateSelicRequestHeaders(token);
    ... resto igual ...
