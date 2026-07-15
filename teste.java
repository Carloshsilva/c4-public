private ResponseEntity<String> generateRateLimitExceededResponse(Exception exception, String requestId) {
        logger.warn("A requisicao {} foi recusada por limite de taxa: {}", requestId, exception.getMessage());
        logger.info("Retornado HTTP 429 Too Many Requests");
        return ResponseEntity.status(429).body("Too Many Requests");
    }
