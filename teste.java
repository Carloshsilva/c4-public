Cole o método sendMessageToSelic do JsonRestClient INTEIRO (linhas ~33-61), com a assinatura completa (parâmetros, tipo de retorno, exceções que declara).
Como ele faz a chamada — restTemplate.exchange(...), postForEntity(...), outro? Ele chega a ter um ResponseEntity em mãos?
Quando a SELIC responde um status de erro (4xx/5xx, e especificamente 429): o RestTemplate lança HttpClientErrorException/HttpServerErrorException automaticamente, ou existe um ResponseErrorHandler customizado que muda esse comportamento?
Em que ponto e por que ele lança HaltedCommunicationException e BadResponseFromSelicException? Um 429 da SELIC cairia em qual desses — ou estouraria como HttpClientErrorException antes de virar exceção de domínio?
Num erro HTTP, os headers da resposta (RateLimit-Limit, RateLimit-Remaining, RateLimit-Reset, Retry-After) ainda são acessíveis? De onde — do ResponseEntity (no sucesso) e/ou da exceção do RestTemplate (no erro)?
Além do ProcessingService, algum outro lugar chama sendMessageToSelic? (se eu mudar a assinatura, preciso saber o que mais afeta)
Como o projeto mapeia exceção de domínio → status HTTP na resposta ao cliente? Existe um @ControllerAdvice/@ExceptionHandler (provavelmente em controllers/ ou exceptions/)? Cole-o.
As exceções em exceptions/ seguem um padrão (herdam de uma base comum? recebem código/mensagem no construtor)? Cole uma simples como exemplo (ex.: BadResponseFromSelicException) — vou precisar criar uma exceção de rate limit no mesmo formato.
