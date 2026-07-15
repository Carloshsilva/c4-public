# Plano de integração — Rate Limiter no `selicconecta-outbound`

Projeto real: `br.com.b3.middlewares.selicconecta.outbound` (Maven, Spring Boot 3.3, Java 21).
Objetivo: enxertar o rate limiter de **saída** (chamadas à SELIC), uma classe por vez,
sempre buildando, sem quebrar os 243→? testes/warnings existentes.

---

## Atores e o loop de trabalho

- **Claude (aqui):** tem o histórico (lógica, 30 cenários, decisões e porquês). Escreve as instruções pro Devin e revisa os retornos. Não vê o projeto.
- **Devin/SWE-1.6 (outra máquina):** vê o código real, aplica, roda. Não tem o histórico.
- **Você:** a ponte. Leva instrução pro Devin, traz diagnóstico/erros pro Claude. (Pode mandar **foto** no lugar do texto do Devin — Claude pode pedir uma foto específica.)

**Loop por passo:** (diagnóstico p/ Devin → você traz) → Claude escreve instrução → você aplica no Devin (plan→revisa→executa) → **`mvn clean install`** → verde? próximo. Vermelho? traz o erro.

---

## Regras de ouro

- [ ] **Baseline antes de começar:** anote o nº atual de "Problems" (243) e confirme que `mvn clean install` passa HOJE, sem você ter tocado em nada. Essa é a linha de base.
- [ ] **Uma classe por vez.** Nunca duas peças no mesmo build.
- [ ] **Build a cada passo.** Se ficou vermelho, resolve antes de seguir. (O GitHub vai fazer o mesmo no push.)
- [ ] **Teste junto da classe** (quando a classe tiver lógica que valha testar — ver notas).
- [ ] **Não mexer no `ProcessingService` até o fim.** Ele é a "main de verdade"; entra por último, com tudo já dentro e verde.

---

## Ordem das peças (dependência + complexidade crescente)

1. **Fase 0** — Diagnósticos (não escreve código)
2. **Retorno** (enum) — o mais simples; valida o loop de trabalho
3. **Cota** (POJO + verbos) + teste
4. **Reserva** (value object)
5. **Clock + constantes** (bean `Clock` + `ratelimit.*` no yml)
6. **Infra Hazelcast** (dependência no pom + `HazelcastConfig`) — checkpoint especial
7. **RateLimiter** (o núcleo) + testes
8. **Encaixe no `ProcessingService`** (a cirurgia) — por último

---

## FASE 0 — Diagnósticos (rode no Devin, traga as respostas)

Pode rodar todos numa janela de "análise" (modo read-only). Traga o que cada um responder.

- [ ] **D1 — Logging.**
  > "Analise o padrão de logging deste projeto. O `util/LoggingUtil` é usado para TODO log ou só para logs de request de negócio? Como as classes de `services` (ex.: `ProcessingService`, `JsonRestClient`, `ValidationService`) obtêm e usam o logger — via `LoggingUtil`, via `LoggerFactory.getLogger()` direto, ou outro? Cole 2-3 linhas reais de log dessas classes."

- [ ] **D2 — Config / constantes.**
  > "Como o projeto lê configuração? Descreva a estrutura do `application.yml` (há perfis cer/dev/qab) e como valores são injetados (@Value, @ConfigurationProperties?). O `util/Constants.java` guarda o quê? Onde eu adicionaria novas constantes de configuração que precisam variar por ambiente?"

- [ ] **D3 — Testes.**
  > "Qual o padrão de testes? JUnit 4 ou 5? Usa Mockito? Onde ficam (src/test espelhando o pacote)? Cole o conteúdo de um teste existente pequeno (ex.: `services/AdditionalInformationParserTest.java`) para eu ver o estilo (nomes, asserts, mocks)."

- [ ] **D4 — Hazelcast já existe?**
  > "O `pom.xml` já tem alguma dependência de Hazelcast? Cole as `<dependencies>` do pom e a versão do Spring Boot no `<parent>`."

- [ ] **D5 — Ponto de encaixe (o mais importante).**
  > "No `ProcessingService`, mostre o trecho onde a chamada à SELIC é disparada. E no `JsonRestClient`, mostre o método que faz o HTTP: qual o tipo de retorno (ResponseEntity?), e como se acessam os headers da resposta (RateLimit-Limit, RateLimit-Remaining, RateLimit-Reset, Retry-After)? É aqui que o rate limiter vai abraçar a chamada."

- [ ] **D6 — Fonte do R (concorrência de saída).**
  > "O `ConcurrencyLimiter` usa um ThreadPoolExecutor. Qual o tamanho do pool (core/max)? Esse pool limita as requisições que ENTRAM. Existe algum outro pool/limite entre o processamento e a chamada HTTP à SELIC (no `JsonRestClient`)? Cada tarefa do pool resulta em exatamente UMA chamada à SELIC?"

- [ ] **D7 — Pacote de destino.**
  > "Sugira onde criar um novo subpacote para o rate limiter, seguindo a convenção (vi `configs`, `data`, `services`, `util`). Faz sentido `services/ratelimit` ou um pacote próprio `ratelimit`?"

> Ao trazer as respostas (ou fotos), Claude ajusta TODO o resto do plano ao padrão real. **D5 e D6 são os que mais importam** — o encaixe e o R.

---

## FASE 1 — `Retorno` (enum)

- Depende de: nada. Serve pra validar o loop inteiro com risco zero.
- [ ] Claude escreve a instrução (classe já no pacote de D7, comentada no padrão do projeto).
- [ ] Devin aplica. **Sem teste** (enum trivial).
- [ ] `mvn clean install` → verde.

## FASE 2 — `Cota` (POJO + verbos) + teste

- Depende de: nada (Cota não conhece Hazelcast; só implementa Serializable).
- [ ] Claude escreve `Cota` (renomeada, comentada pra "outra pessoa entender a lógica", sem System.out).
- [ ] Devin aplica → build.
- [ ] **Teste da Cota** (vale a pena — os verbos têm lógica validada nos 30 cenários): Claude escreve o teste no padrão de D3 cobrindo `incrementa`, `atualizaComHeader`, `renovaOuPreservaContador` (renova vs preserva), `bloqueia`/`desbloqueia`, `copiaDe`.
- [ ] Devin aplica teste → `mvn clean install` → verde.

## FASE 3 — `Reserva` (value object)

- Depende de: nada.
- [ ] Claude escreve. **Teste opcional** (é um portador de flags; teste só se o padrão do time exigir cobertura).
- [ ] Build → verde.

## FASE 4 — `Clock` + constantes

- [ ] Bean `Clock` (`@Bean Clock clock() { return Clock.systemUTC(); }`) no lugar que D2 indicar para @Configuration.
- [ ] Constantes `ratelimit.*` no `application.yml` (formato/local de D2), por ambiente se fizer sentido:
      `limite-documental`, `n`, `margem`, `reserva`.
- [ ] **`reserva` = 2 × (tamanho do ThreadPoolExecutor de D6)** — NÃO o placeholder 20. Confirmar na fonte.
- [ ] Build → verde (config não quebra nada).

## FASE 5 — Infra Hazelcast (pom + HazelcastConfig)  ⚠️ checkpoint especial

- Depende de: D4 (saber se já tem Hazelcast).
- [ ] Adicionar ao pom: `com.hazelcast:hazelcast:5.5.0` e `com.hazelcast:hazelcast-spring:5.5.0` (casar com a versão que a POC validou).
- [ ] Trazer o `HazelcastConfig` (cluster) da POC, adaptado ao pacote real. Sem CP Subsystem (usamos `IMap.lock`).
- [ ] ⚠️ **Risco:** subir Hazelcast no boot pode fazer os TESTES tentarem formar cluster e travar/lentificar. Ação: garantir que o contexto de teste NÃO forme cluster (perfil de teste que desabilita, ou instância single-member). Diagnóstico rápido no Devin se algum teste pendurar.
- [ ] `mvn clean install` → verde **E** os testes rodam no mesmo tempo de antes (não penduram).

## FASE 6 — `RateLimiter` (o núcleo) + testes

- Depende de: Cota, Reserva, Retorno, Clock, Hazelcast (Fases 2-5), e do padrão de log (D1).
- [ ] Claude escreve o `RateLimiter` adaptado: log no padrão de D1 (troca os System.out), `Clock` no lugar do Relogio, pacote de D7, headers de D5, comentários explicativos.
- [ ] Devin aplica → build.
- [ ] **Testes do RateLimiter** (re-validam a lógica dos 30 cenários no projeto real): Mockar `HazelcastInstance`/`IMap` + `Clock.fixed`. Cobrir os casos-chave:
      coordenado encaminha/recusa; descoordenado rem>R e cold start; 429 bloqueia; desbloqueio pelo relógio; reancoragem (âncora + 429 nas concorrentes).
      (Opcional forte: portar cenários do simulador como casos de teste — vira a suíte de regressão.)
- [ ] `mvn clean install` → verde.
- [ ] **Claude revisa** o RateLimiter final contra a lógica (é a peça mais crítica).

## FASE 7 — Encaixe no `ProcessingService`  🔴 a cirurgia

- Depende de: TUDO acima verde. Guiado por D5.
- [ ] Claude escreve a instrução do encaixe: DEPOIS das regras de negócio, ABRAÇANDO a chamada do `JsonRestClient`:
      `reservar()` → se negado, 429 ao cliente → chama SELIC → `aplicarResposta(...)` no `finally` (SEMPRE) → devolve.
- [ ] Devin aplica em modo plan; **você revisa o diff com cuidado** antes de executar.
- [ ] `mvn clean install` → verde. Testes existentes do `ProcessingService` continuam passando.
- [ ] **Claude revisa** o encaixe (ponto de maior risco).

---

## Depois do build verde: validação distribuída (obrigatória)

A LÓGICA está validada (30 cenários). A CAMADA DISTRIBUÍDA (IMap.lock, cluster, âncora) NÃO.
Suba **2 instâncias** e teste, nesta ordem: coordenado básico → **concorrência** (carga das duas, soma nunca > L) → 429/desbloqueio → **partição e volta** (reancoragem). Ver `ROTEIRO.md` do pacote.

---

## Notas que Claude vai puxar quando chegar a hora

- **R real** = 2 × pool do ThreadPoolExecutor (D6), no `application.yml`.
- **Log** = padrão de D1 (provável `LoggingUtil` ou SLF4J direto).
- **Headers** = nomes/acesso de D5 (RestTemplate `ResponseEntity.getHeaders()`).
- **Unidade de tempo** = segundos em tudo (`Clock` → `instant().getEpochSecond()`); confirmar que Reset/Retry-After da SELIC vêm em segundos.
- **ConcurrencyLimiter (entrada) ≠ nosso rate limiter (saída):** não conflitam.
