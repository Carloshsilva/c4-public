# Roteiro de integração — Rate Limiter distribuído

Pacote pronto para Spring Boot 3.3.0 + Hazelcast 5.5.0 + Java 21.
`IMap.lock` (sem CP Subsystem), 2 instâncias.

---

## 0. Leia isto primeiro (honestidade sobre o que está validado)

- **A LÓGICA** (verbos da `Cota`, réguas de decisão, bloqueio, header) foi validada por **30 cenários** no simulador. Nesses pontos, confie.
- **A CAMADA DISTRIBUÍDA** (`IMap.lock`, detecção de cluster, reancoragem por âncora) é **NOVA e não foi validada** — foi desenhada e justificada, mas nunca rodou. **Trate como esqueleto forte para revisar e testar**, não "cola e esquece".
- Não foi compilado no ambiente onde foi gerado. **Compile e ajuste importes/tipos.**

---

## 1. O que vai em cada lugar

| Arquivo | Onde | Ação |
|---|---|---|
| `src/main/java/com/exemplo/ratelimit/*.java` | seu projeto | copiar (ajustar o pacote se não for `com.exemplo`) |
| `application-ratelimit.properties.snippet` | `application.properties` | **mesclar** as linhas |
| `HazelcastConfig-adicao.snippet.txt` | seu `HazelcastConfig` | opcional (backup do IMap) |
| `exemplo/IntegracaoExemplo.java.txt` | — | **não copiar**; é guia do encaixe |
| `pom.xml` | — | nada a fazer; suas deps (Hazelcast/hazelcast-spring) já bastam |

As classes: `Cota` (dado, vai no IMap), `Retorno`, `Relogio`/`RelogioSistema` (tempo), `Reserva` (resultado da reserva), `RateLimiter` (o bean maestro).

---

## 2. O ÚNICO ponto que você pluga: o encaixe

Dentro do seu serviço de encaminhamento, **depois das regras de negócio**, abraçando a chamada HTTP (ver `exemplo/IntegracaoExemplo.java.txt`):

```
Reserva reserva = rateLimiter.reservar();
if (!reserva.permitido()) return 429_ao_cliente;
try {
    resposta = <sua chamada HTTP ao provedor>;
    return resposta_ao_cliente;
} finally {
    rateLimiter.aplicarResposta(reserva, status, headers); // SEMPRE, mesmo em falha
}
```

Regras de ouro do encaixe:
1. **RateLimiter depois das regras de negócio** (só conta o que vai de fato ao provedor).
2. **`aplicarResposta` SEMPRE** — mesmo se a chamada HTTP lançar exceção. É num `finally`. Se você esquecer isso, a reancoragem pode travar (o flag nunca libera).
3. **Não segure lock durante o HTTP** — o RateLimiter já cuida disso (o HTTP acontece ENTRE os dois locks, no seu código, fora deles).
4. **`headers` cru** — passe o mapa de cabeçalhos como veio; o RateLimiter interpreta (`RateLimit-*`, `Retry-After`).

---

## 3. Ajustes que você PRECISA conferir

- **Nomes dos cabeçalhos** (`RateLimiter.java`, constantes `H_LIMIT` etc.): confirme que a SELIC CONECTA usa `RateLimit-Limit / RateLimit-Remaining / RateLimit-Reset / Retry-After`. Se usar outros, troque as constantes.
- **Unidade de tempo**: `RelogioSistema.agora()` devolve **segundos**. `RateLimit-Reset` e `Retry-After` também devem estar em segundos. Se o provedor mandar em outra unidade, ajuste.
- **`ratelimit.reserva=20`**: é `2 x C`, com C=10 threads por instância. Se a concorrência mudar, mude aqui (reinicia sem recompilar).
- **Serialização**: `Cota` usa `Serializable` (simples, suficiente a ~5 req/s). Se quiser performance, migre depois para `IdentifiedDataSerializable` do Hazelcast.

---

## 4. Como testar (isto é obrigatório — a camada distribuída é nova)

Suba **2 instâncias** (como você já fez na POC) e valide, em ordem:

1. **Coordenado básico**: 1 requisição encaminha, o `Comp` no IMap atualiza, a outra instância enxerga o mesmo `Comp`.
2. **Concorrência (o teste que importa)**: dispare carga das duas instâncias ao mesmo tempo, com `L` baixo, e confirme que a soma **nunca** passa de `L` (o `IMap.lock` segurando o read-modify-write). É o cenário que o simulador NÃO cobria.
3. **429**: force um 429 do provedor (ou um mock) e confirme bloqueio nas duas + desbloqueio pelo relógio.
4. **Partição (o mais delicado)**: derrube a comunicação entre os nós (pare um, ou bloqueie a porta do cluster), confirme que cada um cai em descoordenado (`rem > R`); religue e confirme a **reancoragem** (a 1a requisição vira âncora, as concorrentes tomam 429 até reancorar).

Use a **suíte do simulador** (os 30 cenários) como rede de regressão da LÓGICA: se algum comportamento divergir do que lá foi validado, a transcrição introduziu bug.

---

## 5. Pontos conscientemente em aberto (não são bugs; são decisões futuras)

- **Detecção de "virada de janela"** por `reset` novo > anterior é frágil no mundo real (o reset decresce entre fotos). Funciona nos cenários; revisite se ver renovação errada em produção.
- **Risco irredutível da requisição-âncora**: a 1a requisição pós-partição é encaminhada sob incerteza; no pior caso, é a que leva o 429-caro. É UMA requisição, o R cobre, e cai no fluxo de bloqueio. Inevitável — você não sabe o estado do provedor sem mandar algo real.
- **`comunicando = size() >= N`** assume N=2. Com mais nós, a régua de "cluster saudável" precisa ser repensada.

---

## 6. Mapa mental (o "porquê", para quando esquecer)

- **Segurança > vazão**: troca 429-baratos (ao cliente) para nunca pagar o 429-caro (60s de castigo do provedor).
- **Duas defesas**: o **lock** impede reservar a mesma vaga; o **R** cobre o in-flight entre reservar e a resposta voltar.
- **Confie no `rem`, não em foto velha**: o `rem` conta as duas instâncias, mas só no instante da foto; por isso a reancoragem ancora numa resposta **fresca**.
