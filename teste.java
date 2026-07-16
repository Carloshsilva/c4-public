Os testes JsonRestClientTest e ProcessingServiceTest ainda passam depois das mudanças no JsonRestClient (que agora injeta RateLimiter e chama reservar()/aplicarResposta() dentro do sendMessageToSelic)? Especificamente:

Como o JsonRestClientTest instancia/mocka o JsonRestClient? Ele fornece um mock do RateLimiter? Se não, o campo rateLimiter fica null e o reservar() daria NullPointerException.
Rode mvn test (ou só os testes dessas duas classes) e me diga se algum falha, e qual o erro.
Se algum falha, o que precisa ser ajustado: adicionar um @Mock RateLimiter e configurar reservar() para retornar uma Reserva permitida?
