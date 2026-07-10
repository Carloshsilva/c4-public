# Rate limiting distribuído (A e B) — Lógica do balde e 26 cenários

**Como usar este documento:** cada testador recebe as **9 premissas**, o bloco de **Regras gerais do balde** abaixo e **o cenário dele**. Cada cenário foi escrito para ser lido sozinho — por isso os números e regras se repetem dentro dos cenários. Isso é de propósito.

---

## Regras gerais do balde

**A ideia central:** enquanto A e B conseguem se falar, existe **um balde só**, compartilhado — os dois leem e descontam do mesmo número. Quando param de se falar, viram **dois baldes dessincronizados**: cada servidor fica com a sua cópia, que só enxerga o próprio gasto. Todo o desenho abaixo existe para que, mesmo dessincronizados, a soma dos dois nunca passe de 300.

**O que fica guardado no balde:**

1. **Última leitura da API** — toda resposta traz um header dizendo em que janela estamos e quantas chamadas ainda cabiam naquele instante. Guardamos sempre a leitura mais nova.
2. **Chamadas no ar** — já enviadas, ainda sem resposta.
3. **Chamadas incertas** — deram timeout; não sabemos se a API contou. Ficam contadas como gastas até a janela virar.

**Saldo estimado = última leitura − chamadas no ar − chamadas incertas.** É esse número que decide se pode enviar.

**COM comunicação (balde único):**

- Pode enviar enquanto o saldo estimado ficar **acima de 20** (o ponto de parada).
- Antes de enviar, o servidor desconta no balde compartilhado — o outro enxerga o desconto. Como a atualização leva um instante para aparecer do outro lado, vale a **regra dos 5**: nenhum servidor fica mais de 5 chamadas à frente do que o outro já confirmou ter enxergado. Se mensagens entre eles se perderem, o erro máximo é 5.

**SEM comunicação (dois baldes):**

- Cada servidor obedece uma **cota fixa pessoal: 140 chamadas por janela** (135 de tráfego + 5 de teste). 140 + 140 = 280 → sobra folga de 20 mesmo que os dois gastem tudo sem se enxergar.
- **Exceção — a janela em que a comunicação caiu:** nessa janela a cota é menor: **metade do saldo conhecido no momento da queda** (nunca mais que 140), porque parte das 300 já tinha sido gasta em conjunto. Da primeira virada de janela em diante, vale a cota cheia de 140.
- A cópia local do balde é só **freio**: se o header mostrar menos saldo do que o esperado, desacelera ou para. Header mostrando saldo alto **nunca** autoriza passar da cota.

**Reserva intocável:** as últimas **10 fichas** da janela ninguém usa. Nunca. Em nenhum modo.

**Chamada de teste:** quando o tráfego está parado (chegou no 20 ou na cota) e há pedido esperando, sai **1 chamada sozinha**, só para descobrir pelo header da resposta se a janela virou. Uma por vez, no máximo 5 por servidor por janela, sem tocar na reserva de 10. Ela nunca é disparada "depois de X segundos" — relógio local não vale (premissa 5); ela só sai porque há pedido esperando.

**Como saber que a janela virou (sem relógio):** dentro de uma mesma janela, o saldo do header **só diminui**. Se uma resposta mostrar saldo **maior** que a última leitura (ou identificar uma janela nova), a janela virou: balde cheio de novo, contadores da janela zerados.

**Respostas fora de ordem:** uma leitura só substitui a atual se for mais nova — mesma janela com saldo **menor**, ou janela nova. Leitura velha nunca aumenta o saldo.

**Penalizado (tomou 429):** nada sai, de nenhum balde, até a liberação.

**Regra de ouro (premissa 9):** na dúvida, não envia e desconta a mais. Errar para menos custa capacidade e a próxima janela corrige sozinha. Errar para mais custa 60 segundos de bloqueio para o IP inteiro.

**Sobre os números:** 20 (ponto de parada), 10 (reserva), 140 (cota), 5 (regra dos 5), 5 (testes por janela) são exemplos, calibráveis. As regras valem com outros valores, desde que: cota A + cota B fique bem abaixo de 300, e o ponto de parada fique acima da reserva.

---

## Família A — Chegou chamada (COM comunicação)

### Cenário 1 — Primeira chamada, ainda sem header

**1. Situação:** O servidor acabou de entrar no ar, nunca chamou a API, e chegou o primeiro pedido do sistema interno. A conversa com o outro servidor está funcionando.

**2. O que o servidor sabe / não sabe:** Sabe que consegue perguntar ao outro. Não sabe quantas fichas restam na janela, nem em que janela estamos, nem se existe um bloqueio de 60s em andamento (herdado de antes de ele ligar).

**3. Lógica do balde:**
- **Tirar:** Antes de qualquer coisa, pergunta ao outro servidor se ele já tem uma leitura da API. Se tem, adota essa leitura e segue as regras normais (enviar enquanto o saldo estimado ficar acima de 20). Se **nenhum dos dois** tem leitura, sai **exatamente 1 chamada de teste**; todos os outros pedidos esperam na fila.
- **Atualizar:** O header da resposta dessa chamada vira a **primeira leitura** do balde compartilhado — os dois servidores passam a usar. Se a resposta for um 429 (bloqueio), os dois congelam: nada mais sai até a liberação.
- **Fonte da verdade:** A informação do outro servidor primeiro; se ele não tem nada, o header da primeira resposta.

**4. Risco:** Despejar a fila inteira sem saber o estado do balde — a janela pode estar quase gasta ou já bloqueada. Limitando a 1 chamada, o pior caso é 1 chamada na hora errada.

**5. Comportamento esperado:** Perguntar antes de gastar. Se ninguém sabe nada, 1 chamada de teste descobre o estado; a fila só anda depois que a resposta dela chegar.

### Cenário 2 — Tenho leitura fresca, nada pendente

**1. Situação:** Operação normal. Acabou de chegar uma resposta da API com header, e não há nenhuma chamada esperando resposta.

**2. O que o servidor sabe / não sabe:** Sabe quantas fichas sobravam no instante da última resposta e em que janela estamos. Tudo que o outro servidor gastou **até** aquele instante já está dentro desse número; o que ele gasta **depois** aparece no balde compartilhado, porque cada envio é descontado nele.

**3. Lógica do balde:**
- **Tirar:** Sim, do balde compartilhado, se duas condições valem: (a) saldo estimado acima de **20**; (b) este servidor não está mais de **5 chamadas à frente** do que o outro já confirmou ter enxergado. Cumprindo as duas: desconta 1 no balde (o outro enxerga) e envia.
- **Atualizar:** O desconto aparece na hora para os dois. Quando a resposta voltar, o header dela vira a nova leitura — se for mais novo que o atual.
- **Fonte da verdade:** O header da API + o balde compartilhado entre os servidores.

**4. Risco:** Os dois dispararem rajada no mesmo instante. A regra dos 5 somada à parada em 20 cobre isso: mesmo no pior cruzamento de mensagens, ninguém encosta no zero real.

**5. Comportamento esperado:** Fluxo normal — desconta, envia; resposta chega, leitura atualiza.

### Cenário 3 — Tenho leitura velha, com chamadas no ar

**1. Situação:** O servidor mandou chamadas depois da última resposta e elas ainda não voltaram. A leitura que ele tem já está desatualizada.

**2. O que o servidor sabe / não sabe:** Sabe a última leitura, quantas chamadas **suas** estão no ar e quantas o outro descontou no balde. Não sabe se as chamadas no ar já foram contadas pela API.

**3. Lógica do balde:**
- **Tirar:** Sim, se sobrar folga depois de descontar tudo: **última leitura − chamadas no ar dos dois − incertas dos dois > 20**. Se houver dúvida sobre uma chamada (não sabe se já descontou), desconta de novo — descontar em dobro por um instante é aceitável; enviar sem descontar não é.
- **Atualizar:** Cada resposta que chega "quita" a sua chamada (sai da lista de no ar) e, se o header for mais novo, vira a nova leitura. Header atrasado é ignorado para o saldo: leitura velha nunca aumenta o saldo.
- **Fonte da verdade:** O header, corrigido pelos descontos das pendências dos dois servidores.

**4. Risco:** Dois erros possíveis: esquecer de descontar uma chamada no ar (saldo inflado → estouro) ou aceitar um header velho como se fosse novo (mesmo efeito). As regras acima proíbem os dois.

**5. Comportamento esperado:** Contabilidade pessimista: tudo que saiu conta como gasto até prova em contrário; cada resposta que volta corrige a conta.

### Cenário 4 — Quase no limite

**1. Situação:** O saldo estimado do balde compartilhado está baixo: entre 20 (o ponto de parada) e mais ou menos 35. Qualquer erro de contagem agora vira estouro.

**2. O que o servidor sabe / não sabe:** Sabe que a folga é pequena. Sabe quantas chamadas estão no ar. Não sabe quais delas a API já contou.

**3. Lógica do balde:**
- **Tirar:** Sim, mas **uma por vez**: cada servidor manda no máximo 1 chamada e espera a resposta voltar antes da próxima. Nas últimas fichas antes do 20, cada envio é combinado explicitamente com o outro servidor ("vou mandar 1, ok?") — na prática, a regra dos 5 vira regra do 1.
- **Atualizar:** Como sempre: a resposta quita a chamada e atualiza a leitura. Aqui a prioridade é **esperar respostas** em vez de enviar novas.
- **Fonte da verdade:** O header + a confirmação explícita do outro servidor, ficha a ficha.

**4. Risco:** Os dois correndo pelas mesmas últimas fichas; ou o paralelismo interno de um servidor soltando várias chamadas de uma vez.

**5. Comportamento esperado:** Desacelerar, mandar uma por vez, e **parar quando o saldo estimado chegar a 20** — nunca tentar usar as fichas finais.

### Cenário 5 — Balde no ponto de parada, sem penalidade

**1. Situação:** O saldo estimado chegou a 20 e o tráfego normal parou. A API **não** aplicou bloqueio nenhum — paramos por segurança antes de chegar no limite real.

**2. O que o servidor sabe / não sabe:** Sabe que gastou tudo que era planejado gastar nesta janela. **Não sabe quando a janela vira** — sem relógio, só uma resposta da API pode contar isso.

**3. Lógica do balde:**
- **Tirar:** Tráfego normal, não. **Chamada de teste, sim:** se há pedido esperando, sai 1 chamada de teste por vez, e os dois servidores combinam quem manda (uma regra simples e fixa de revezamento — nunca os dois juntos). Limites: no máximo 5 testes por servidor por janela, e o saldo estimado nunca pode passar de 10 (a reserva intocável). Ou seja: as chamadas de teste vivem exatamente na folga entre o 20 e o 10.
- **Atualizar:** A resposta do teste ou confirma "mesma janela, saldo parecido" (continua parado) ou mostra saldo bem maior — a janela virou: balde reabastecido, contadores zerados, fila liberada. O resultado aparece no balde compartilhado, então o outro fica sabendo na hora.
- **Fonte da verdade:** O header trazido pela chamada de teste + o balde compartilhado.

**4. Risco:** Testar demais e comer a reserva de 10; ou os dois testarem ao mesmo tempo. O orçamento de 5 e o revezamento combinado resolvem.

**5. Comportamento esperado:** Segurar a fila, testar de leve (só quando há pedido esperando), e reabrir o fluxo quando um header mostrar a janela nova.

### Cenário 6 — Penalizado

**1. Situação:** O bloqueio de 60s está ativo — um 429 já foi recebido e os dois servidores sabem. Chega um pedido novo.

**2. O que o servidor sabe / não sabe:** Sabe que o IP inteiro está bloqueado e o que a resposta de bloqueio informou (prazo de liberação, se veio). **Não sabe** com certeza se tentar durante o bloqueio faz a pena recomeçar do zero.

**3. Lógica do balde:**
- **Tirar:** **Não. De nenhum balde. Nem chamada de teste.** O pedido espera na fila (ou é recusado — decisão de produto). Enquanto não for confirmado que tentar durante o bloqueio é inofensivo, a regra é silêncio total até o prazo que a **API** informou na resposta de bloqueio. Passado esse prazo, a volta começa com **1 chamada de teste sozinha** — nunca despejando a fila.
- **Atualizar:** Nada sai, então nada muda. O estado "penalizado", com a informação da API, é a verdade vigente e está no balde compartilhado — os dois respeitam.
- **Fonte da verdade:** A própria resposta de bloqueio da API (prazo e instruções que ela deu).

**4. Risco:** O "só mais uma" reiniciar os 60s. E, na liberação, os dois despejarem a fila juntos e estourarem de novo na hora — não sabemos como o saldo volta depois do bloqueio.

**5. Comportamento esperado:** Congelar tudo. Sair do bloqueio com 1 chamada de teste. Operar a primeira janela depois do bloqueio em modo extra-conservador (por exemplo, metade dos limites normais).

---

## Família A — Chegou chamada (SEM comunicação)

### Cenário 7 — Primeira chamada, sem header

**1. Situação:** O servidor acabou de entrar no ar, nunca chamou a API, chegou o primeiro pedido — e ele **não consegue** falar com o outro servidor.

**2. O que o servidor sabe / não sabe:** Não sabe nada da janela (nem saldo, nem se há bloqueio em andamento) e não sabe nada do outro — que pode estar vivo e gastando agora mesmo.

**3. Lógica do balde:**
- **Tirar:** Sai **1 chamada de teste**; o resto espera. Com o header dessa resposta na mão, o servidor define seu limite **desta janela** assumindo o pior sobre o outro: pega o saldo do header, subtrai a cota inteira do outro (140) e mais uma margem de segurança — pode gastar no máximo isso (e nunca acima de 140). Se a conta der zero ou negativa, fica só nas chamadas de teste (1 por vez, máximo 5) até a janela virar. **Da primeira virada de janela em diante:** cota fixa normal de 140 (135 de tráfego + 5 de teste).
- **Atualizar:** Só as próprias respostas atualizam a cópia local do balde. O outro não fica sabendo de nada.
- **Fonte da verdade:** O header como freio + o limite fixo calculado acima.

**4. Risco:** O header pode mostrar saldo alto **agora**, mas o outro ainda vai gastar a cota inteira dele nesta janela — por isso ela é subtraída por inteiro. Perde-se capacidade nesta primeira janela; a premissa 9 aceita esse custo.

**5. Comportamento esperado:** Nascer pequeno e desconfiado; normalizar (cota 140) na primeira virada de janela.

### Cenário 8 — Tenho leitura fresca

**1. Situação:** Operação normal sem comunicação: cada servidor com seu balde. Acabou de chegar resposta da API com header; nada pendente.

**2. O que o servidor sabe / não sabe:** Sabe quantas fichas sobravam no instante da resposta — e esse número **já inclui** o que o outro gastou até ali. **Não sabe nada** do que o outro gasta depois desse instante.

**3. Lógica do balde:**
- **Tirar:** Sim, se as duas condições valem: (a) o gasto deste servidor na janela atual ainda está **abaixo da sua parte de tráfego da cota** — 135 numa janela normal; se esta é a janela em que a comunicação caiu, vale o limite reduzido definido na hora da queda (metade do saldo então conhecido); (b) o saldo estimado local (última leitura − próprias chamadas no ar − próprias incertas) está acima de **10** (a reserva intocável). O header **nunca** autoriza passar da cota — ele só freia.
- **Atualizar:** Só na cópia local: a resposta atualiza a leitura deste servidor. O outro não fica sabendo.
- **Fonte da verdade:** A **cota fixa** manda; o header é o cinto de segurança por baixo.

**4. Risco:** A tentação de "aproveitar" um saldo alto no header e passar da cota — se os dois pensarem assim, a soma estoura as 300. Header alto **nunca** acelera ninguém.

**5. Comportamento esperado:** Fluir dentro da cota, com o header vigiando por baixo.

### Cenário 9 — Tenho leitura velha, com chamadas no ar

**1. Situação:** Sem comunicação, o servidor mandou várias chamadas e as respostas ainda não voltaram.

**2. O que o servidor sabe / não sabe:** Tem a última leitura (já velha) e a lista das próprias chamadas no ar. Tem **zero** informação sobre o que o outro fez depois daquele header.

**3. Lógica do balde:**
- **Tirar:** Mesma regra do modo sem comunicação: (a) gasto da janela abaixo da cota pessoal (135 de tráfego numa janela normal; se esta é a janela da queda de comunicação, vale o limite reduzido definido na hora da queda); (b) saldo estimado local (leitura − próprias no ar − próprias incertas) acima de 10. O que protege da cegueira sobre o outro **não é o header — é a cota**: se cada um respeita a sua, a soma fica em 280 no máximo, aconteça o que acontecer no silêncio.
- **Atualizar:** Cada resposta que volta quita a sua chamada e, se o header for mais novo, atualiza a leitura local.
- **Fonte da verdade:** A cota fixa + o header defasado como freio.

**4. Risco:** O saldo estimado local pode estar otimista (o outro acelerou e a leitura antiga não mostra). Isso é inofensivo **enquanto os dois honram as cotas** — o furo só existe se alguém ignorá-la.

**5. Comportamento esperado:** Igual ao fluxo normal sem comunicação, só que mais desconfiado: com muitas chamadas no ar acumuladas, desacelera.

### Cenário 10 — Quase no limite

**1. Situação:** Sem comunicação, o servidor está perto de um dos seus dois tetos: faltam poucas chamadas para completar a cota pessoal da janela, **ou** o saldo estimado local está se aproximando da reserva de 10 — o que chegar primeiro.

**2. O que o servidor sabe / não sabe:** Sabe que a folga pessoal está acabando. Se quem apertou foi o header (saldo baixo demais para a altura da janela), é sinal de que o outro acelerou — ou de que existe mais alguém gastando nesse IP.

**3. Lógica do balde:**
- **Tirar:** Uma por vez, esperando a resposta (e o header novo) voltar antes da próxima. Se o saldo do header estiver estranhamente baixo para o ponto da janela, **para antes** mesmo de completar a cota.
- **Atualizar:** Cada resposta atualiza a leitura local antes da próxima retirada.
- **Fonte da verdade:** O **mais restritivo** entre a cota fixa e o header.

**4. Risco:** Os dois servidores chegarem ao fim das cotas quase juntos, com chamadas cruzadas no ar — coberto pela folga de 20 (300 − 280) e pela reserva de 10.

**5. Comportamento esperado:** Desacelerar, parar na cota (ou antes, se o header mandar), esperar a janela virar.

### Cenário 11 — Cota esgotada, sem penalidade

**1. Situação:** Sem comunicação, o servidor gastou as 135 chamadas de tráfego da cota dele (ou o limite reduzido da janela, se a comunicação caiu nesta janela). A API não bloqueou nada — ele parou porque a **regra dele** manda parar aí.

**2. O que o servidor sabe / não sabe:** Sabe que **a parte dele** acabou. Não sabe quando a janela vira nem quanto o outro ainda tem.

**3. Lógica do balde:**
- **Tirar:** Tráfego normal, não. **Chamada de teste, sim:** se há pedido esperando, sai 1 por vez, dentro do orçamento de 5 testes por janela, e só se a última leitura indicar saldo real acima de 10 (a reserva). É assim que ele descobre a virada da janela sem relógio.
- **Atualizar:** A resposta do teste ou confirma a mesma janela (continua parado) ou mostra o saldo bem maior — a janela virou: zera os contadores, a cota renasce (140) e a fila anda.
- **Fonte da verdade:** O header trazido pela chamada de teste.

**4. Risco:** Os dois servidores testando sem se enxergar. Está dimensionado para isso: 5 + 5 = 10 testes cabem na folga de 20 sem tocar a reserva de 10.

**5. Comportamento esperado:** Segurar a fila, testar de leve, reabrir na virada.

### Cenário 12 — Penalizado

**1. Situação:** Sem comunicação, este servidor recebeu um 429: o IP está bloqueado. Chega um pedido novo.

**2. O que o servidor sabe / não sabe:** Sabe que o IP inteiro está bloqueado e o que a resposta informou. **Não consegue avisar o outro**, que pode continuar tentando até tomar o próprio 429.

**3. Lógica do balde:**
- **Tirar:** **Nada, de nenhum balde, nem teste.** Silêncio total até o prazo que a API informou na resposta de bloqueio. Depois do prazo: 1 chamada de teste sozinha, e a primeira janela em modo extra-conservador (por exemplo, metade da cota).
- **Atualizar:** Nada sai; o estado "penalizado" local, com a informação da resposta, é a verdade deste servidor. O outro vai se congelar sozinho quando receber o 429 dele — leva no máximo mais ou menos 1 chamada.
- **Fonte da verdade:** A resposta de bloqueio recebida por este servidor.

**4. Risco:** Se tentativas durante o bloqueio esticam a pena, as idas do outro (que não sabe) prejudicam os dois — sem comunicação, não há como avisar.

**5. Comportamento esperado:** Congelar; sair pela chamada de teste única; e tratar como **incidente**: sem comunicação, com 140 + 140 = 280 de 300, um 429 não deveria acontecer — algo está errado (calibração, um terceiro sistema no mesmo IP, ou alguém furou a regra).

---

## Família B — Mudou o mundo

### Cenário 13 — Parei de ouvir o outro

**1. Situação:** Os dois operavam juntos (balde único) e, de repente, as atualizações do outro servidor param de chegar e de ser confirmadas. Pode ser queda dele ou bloqueio de rede — **para este servidor, é indiferente**.

**2. O que o servidor sabe / não sabe:** Tem a última visão comum do balde, as próprias chamadas no ar e a última coisa que o outro registrou. Não sabe o que o outro faz agora — mas sabe uma coisa importante: pela regra dos 5 do modo junto (ninguém anda mais de 5 chamadas à frente do que o outro confirmou), a visão do outro difere da dele em **no máximo 5 chamadas**.

**3. Lógica do balde:**
- **Tirar:** Muda **imediatamente** para o modo sem comunicação, com uma cota especial válida só **até esta janela virar**: pega o saldo conhecido no momento da queda, tira uma margem de segurança, divide por dois — essa metade (menos as próprias chamadas no ar) é o que este servidor pode gastar. Como o outro (se estiver vivo) faz a mesma conta sobre uma visão que difere em no máximo 5, as duas metades somadas cabem no saldo real. **Da primeira virada de janela em diante:** cota fixa normal de 140 por servidor.
- **Atualizar:** Só localmente, pelos próprios headers. O balde agora é uma cópia particular.
- **Fonte da verdade:** O header como freio + a meia-cota desta janela (e a cota de 140 nas seguintes). A queda é detectada por **eventos**, não por relógio: "registrei K movimentos no balde e não vi nenhuma confirmação do outro". Enquanto a queda não é percebida, a regra dos 5 já limita o avanço às cegas.

**4. Risco:** Os dois dividirem números levemente diferentes (coberto pela margem + os 5 da regra); demorar a perceber a queda (coberto pela regra dos 5).

**5. Comportamento esperado:** Encolher na hora, sem tentar adivinhar se o outro morreu ou é firewall — nada na decisão depende disso.

### Cenário 14 — Voltei a ouvir o outro; ele esteve vivo o tempo todo (era firewall) e gastou no silêncio

**1. Situação:** A comunicação voltou. Descobre-se que o outro esteve vivo o tempo inteiro, operando sem comunicação como este servidor — cada um com seu balde e sua cota reduzida.

**2. O que o servidor sabe / não sabe:** Agora dá para saber o estado do outro: a leitura dele, o que ele gastou e o que ele tem no ar desde o último header dele. Antes de trocar esses dados, os dois baldes estão dessincronizados.

**3. Lógica do balde:**
- **Tirar:** **Durante a reunificação, cada um continua no seu modo sem comunicação** — ninguém acelera antes de o aperto de mão terminar. A reunificação dos dois baldes num só: leitura vencedora = a mais nova das duas (mesma janela → a de menor saldo; janelas diferentes → a mais nova); saldo estimado comum = essa leitura − chamadas no ar dos dois − incertas dos dois − gastos dos dois feitos depois dos headers. Na dúvida, desconta a mais — o próximo header real corrige. Reunificação confirmada pelos dois lados → volta o modo junto (balde único, parada em 20, regra dos 5).
- **Atualizar:** O balde reunificado vira o compartilhado.
- **Fonte da verdade:** Os headers dos dois + a troca de estado entre eles.

**4. Risco:** Otimismo pós-reencontro — "somar as folgas" dos dois em vez de fundir com pessimismo. Contar um gasto em dobro por um momento é o erro **seguro**; contar de menos é o que estoura.

**5. Comportamento esperado:** Aperto de mão, fusão pessimista dos baldes, e só então soltar o modo junto.

### Cenário 15 — Voltei a ouvir o outro; ele acabou de subir (estava morto)

**1. Situação:** A comunicação aparece porque o outro servidor acabou de ligar. Ele se apresenta "novo, sem estado".

**2. O que o servidor sabe / não sabe:** Este servidor sabe tudo que importa (sua leitura e seu saldo estimado). O outro não sabe nada — no máximo pode ter feito 1 chamada de teste de partida antes de a conversa se estabelecer.

**3. Lógica do balde:**
- **Tirar:** Este servidor segue no regime em que estava até entregar seu estado. O outro **adota** a leitura e o saldo deste (sem precisar gastar chamada para descobrir), começa os contadores de janela zerados, e os dois entram em modo junto (balde único, parada em 20, regra dos 5).
- **Atualizar:** O balde deste servidor vira o compartilhado. Se o novato fez 1 chamada de teste antes, ela entra na conta como uma chamada no ar normal.
- **Fonte da verdade:** O estado deste servidor + os headers.

**4. Risco:** O novato gastar uma chamada de teste à toa antes de perguntar — a regra "quem sobe pergunta antes de testar" evita isso.

**5. Comportamento esperado:** Adoção de estado, entrada suave, sem solavanco no balde.

### Cenário 16 — Eu subi e consigo falar com o outro

**1. Situação:** Este servidor acabou de ligar e a comunicação com o outro funciona.

**2. O que o servidor sabe / não sabe:** Não sabe nada da janela — mas sabe a quem perguntar.

**3. Lógica do balde:**
- **Tirar:** **Nada antes de perguntar.** Pede o estado ao outro; se ele tem, adota (leitura + saldo estimado) e passa a seguir as regras normais do modo junto (parada em 20, regra dos 5). Se o outro também não tem nada (subiram juntos), os dois decidem por uma regra fixa e simples (por exemplo: o de identificador menor) quem manda a **única** chamada de teste de partida; o outro espera a resposta.
- **Atualizar:** Estado adotado no balde compartilhado; depois, fluxo normal (respostas atualizam a leitura, descontos circulam).
- **Fonte da verdade:** A troca com o outro; depois, os headers.

**4. Risco:** Os dois testando na largada e gastando 2 fichas às cegas — a regra fixa de desempate evita.

**5. Comportamento esperado:** Perguntar primeiro; chamada de teste só em último caso, e só uma.

### Cenário 17 — Eu subi e NÃO consigo falar com o outro

**1. Situação:** Este servidor acabou de ligar, chegam pedidos, e o outro não responde — pode estar morto ou pode estar vivo, saudável e **gastando**, atrás de um firewall.

**2. O que o servidor sabe / não sabe:** Nada da janela, nada do outro, e nem se o silêncio é morte ou rede.

**3. Lógica do balde:**
- **Tirar:** Sai **1 chamada de teste** de partida; o resto espera. Com o header em mãos, o limite **desta janela** assume o pior: saldo do header − cota inteira do outro (140) − margem de segurança, e nunca acima de 140. Se a conta der zero ou negativa, só chamadas de teste (1 por vez, máximo 5) até a virada. **Da primeira virada de janela em diante:** cota fixa de 140.
- **Atualizar:** Só localmente, no próprio balde.
- **Fonte da verdade:** O header como freio + o limite fixo.

**4. Risco:** Se o outro também acabou de subir, os dois "testam juntos" — 2 fichas gastas, inofensivo. O caso perigoso (o outro vivo a pleno vapor) já está coberto pela subtração da cota cheia dele.

**5. Comportamento esperado:** Nascer pequeno, crescer na virada, e reunificar os baldes quando a comunicação aparecer.

### Cenário 18 — Comunicação instável (cai e volta toda hora)

**1. Situação:** A conversa entre os servidores fica alternando: funciona, cai, volta, cai de novo — em sequência rápida. O balde fica ameaçando "juntar e separar" toda hora.

**2. O que o servidor sabe / não sabe:** Sabe que o canal não é confiável agora. Não sabe por quanto tempo — e não pode medir "tempo" (premissa 5: relógio local não vale).

**3. Lógica do balde:**
- **Tirar:** Regra de amortecimento **sem relógio**: voltar ao modo junto exige a reunificação completa confirmada pelos dois lados. Se o canal cair de novo **antes de este servidor ver uma virada de janela**, a próxima volta ao modo junto fica proibida até passar **uma janela inteira** com o canal de pé — a estabilidade é medida em viradas da API, o único "relógio" que vale. Enquanto isso, modo sem comunicação direto (cota fixa). E a divisão do saldo por 2 só acontece na **primeira** queda da janela; nas quedas seguintes da mesma janela, mantém o menor limite já vigente — sem re-dividir e encolher à toa.
- **Atualizar:** Localmente; reunificações do balde só quando estabilizar.
- **Fonte da verdade:** O header + a cota fixa.

**4. Risco:** Reunificações pela metade gerando dois baldes incoerentes; ou divisões sucessivas derretendo o limite sem necessidade.

**5. Comportamento esperado:** No sacolejo, ficar separado e quieto; voltar ao modo junto só depois de uma janela inteira limpa.

---

## Família C — Chegou resposta da API (COM comunicação)

### Cenário 19 — Resposta normal com header

**1. Situação:** Uma chamada deste servidor voltou com sucesso, trazendo header.

**2. O que o servidor sabe / não sabe:** Sabe o saldo e a janela **no instante daquela resposta**. Pode não saber, de cara, se essa informação é mais nova que a leitura atual — respostas chegam fora de ordem.

**3. Lógica do balde:**
- **Tirar:** Este momento é de depósito, não de saque — mas a atualização pode **destravar** saques (por exemplo: o saldo subiu na conta e saiu da zona de "quase no limite").
- **Atualizar:** Quatro passos: (1) quita a chamada (sai da lista de no ar); (2) compara com a leitura atual — é mais nova se for a mesma janela com saldo **menor**, ou uma janela mais nova; (3) se mais nova, vira a leitura oficial e o saldo estimado é recalculado; se mais velha, é ignorada para o saldo (só quita a chamada); (4) tudo entra no **balde compartilhado** — o outro servidor enxerga.
- **Fonte da verdade:** O header + o balde compartilhado.

**4. Risco:** Um header fora de ordem "devolvendo" fichas fantasmas (proibido pelo passo 2); uma atualização que demora a aparecer para o outro (limitada pela regra dos 5).

**5. Comportamento esperado:** A informação circula e os dois servidores convergem para o mesmo número.

### Cenário 20 — A resposta mostra que a janela virou

**1. Situação:** Chegou uma resposta cujo header mostra a janela nova: o saldo saltou para cima (ou a identificação da janela mudou).

**2. O que o servidor sabe / não sabe:** Sabe que o balde real reabasteceu (300 de novo, menos o que já foi consumido na janela nova). Não sabe se as chamadas que estavam no ar contaram na janela velha ou vão contar na nova.

**3. Lógica do balde:**
- **Tirar:** O fluxo normal reabre, com as regras de sempre: parar quando o saldo estimado chegar a 20, e a regra dos 5 (ninguém anda mais de 5 à frente do que o outro confirmou).
- **Atualizar:** A leitura vira a da janela nova; saldo estimado = saldo novo − chamadas ainda no ar (na dúvida, continuam descontadas — as respostas delas resolvem); zeram-se os contadores por janela **dos dois** (gasto, testes, incertas, e qualquer limite especial criado durante uma queda de comunicação); a virada entra no balde compartilhado na hora.
- **Fonte da verdade:** O header; o balde compartilhado propaga.

**4. Risco:** "Rajada de comemoração" — os dois despejando a fila de uma vez. As regras de sempre (parada em 20 + regra dos 5) já seguram a descida da fila.

**5. Comportamento esperado:** Reabastecer, andar a fila sob as mesmas regras de sempre, contabilidade limpa.

### Cenário 21 — Resposta 429: descobri agora que estourou

**1. Situação:** Uma resposta voltou como 429: o IP acabou de ser bloqueado por 60s. Em algum ponto, a proteção falhou.

**2. O que o servidor sabe / não sabe:** Sabe que o IP está bloqueado e o que a resposta diz sobre a liberação. Ainda não sabe **por que** estourou (corrida acima da margem? um terceiro sistema no mesmo IP? números mal calibrados?).

**3. Lógica do balde:**
- **Tirar:** Nada mais sai — nem o que está na fila.
- **Atualizar:** Este servidor entra em "penalizado", guarda a informação da API e **coloca o estado no balde compartilhado imediatamente** — é a atualização mais prioritária que existe; o outro congela também. Respostas de chamadas antigas que ainda chegarem só atualizam informação; não liberam envio nenhum.
- **Fonte da verdade:** A resposta de bloqueio.

**4. Risco:** A janelinha entre o 429 deste servidor e o congelamento do outro — algumas chamadas dele ainda escapam; se tentativas durante o bloqueio esticam a pena, isso dói.

**5. Comportamento esperado:** Freio de emergência nos dois. Esperar o prazo informado pela API. Voltar com 1 chamada de teste sozinha. Primeira janela extra-conservadora (metade dos limites) e investigação da causa.

### Cenário 22 — Timeout: não sei se contou

**1. Situação:** Uma chamada deste servidor morreu sem resposta (timeout).

**2. O que o servidor sabe / não sabe:** Não sabe se a API chegou a contar essa chamada — e ela pode até "aparecer" contada depois de respostas mais novas.

**3. Lógica do balde:**
- **Tirar:** Nada a tirar por causa do timeout em si. Se o sistema quiser reenviar, o reenvio é **uma chamada nova**, pelas regras normais (parada em 20, regra dos 5) — nunca "de graça".
- **Atualizar:** A chamada muda de "no ar" para "**incerta**": continua descontada do saldo estimado **até a janela virar** — antes disso não dá para provar que não contou; se contou, o header já a incluiu e fica um desconto duplicado, que é o erro seguro. A incerta entra no balde compartilhado: o outro fica sabendo.
- **Fonte da verdade:** Pessimismo até a próxima virada; os headers das outras respostas continuam atualizando o resto.

**4. Risco:** Rajada de timeouts (API instável): o saldo estimado despenca e os servidores ficam **cegos**, sem headers novos. Regra para esse caso: num apagão de respostas, passar a mandar uma por vez mesmo com saldo aparente — a leitura está envelhecendo.

**5. Comportamento esperado:** Descontar na dúvida, deixar a virada da janela limpar as incertas, e desacelerar na proporção da cegueira.

---

## Família C — Chegou resposta da API (SEM comunicação)

### Cenário 23 — Resposta normal com header

**1. Situação:** Sem comunicação (cada servidor com seu balde), uma chamada deste servidor voltou com sucesso, trazendo header.

**2. O que o servidor sabe / não sabe:** Sabe o que o header diz. O outro servidor **não fica sabendo** — e não precisa: a cota fixa dele (140 por janela) o protege sem essa informação.

**3. Lógica do balde:**
- **Tirar:** Momento de depósito, não de saque.
- **Atualizar:** Só na cópia local: quita a chamada; se o header for mais novo (mesma janela com saldo menor, ou janela nova), vira a leitura local e o saldo estimado é recalculado. Nenhum aviso a ninguém.
- **Fonte da verdade:** O header — sempre por baixo do teto da cota fixa (o header freia, nunca autoriza passar da cota).

**4. Risco:** Nenhum novo — só a "solidão da informação", que a cota fixa já paga.

**5. Comportamento esperado:** Leitura local em dia; o modo sem comunicação segue autossuficiente.

### Cenário 24 — A resposta mostra que a janela virou

**1. Situação:** Sem comunicação, o header de uma resposta mostra a janela nova: o saldo saltou para cima (ou a identificação da janela mudou).

**2. O que o servidor sabe / não sabe:** Sabe que o balde real reabasteceu. Não sabe se o outro já percebeu — mas tudo bem: enquanto o outro não percebe, ele continua conservador, o que é seguro.

**3. Lógica do balde:**
- **Tirar:** O fluxo deste servidor reabre, dentro da cota da janela nova.
- **Atualizar:** Zera os contadores locais da janela (gasto, testes, incertas) e encerra qualquer limite reduzido criado na queda de comunicação — a partir daqui vale a **cota cheia de 140** (135 de tráfego + 5 de teste). Saldo estimado = saldo novo − próprias chamadas ainda no ar.
- **Fonte da verdade:** O header. Como detectar a virada sem relógio: dentro de uma janela o saldo só cai; se **subiu**, virou — por definição.

**4. Risco:** Confundir um header fora de ordem com virada — impossível pela regra acima: saldo maior na mesma janela não existe; se está maior, é janela nova.

**5. Comportamento esperado:** A cota renasce, a fila anda; cada servidor no seu ritmo, com a soma garantida em no máximo 280.

### Cenário 25 — Resposta 429

**1. Situação:** Sem comunicação, este servidor recebeu um 429 — o IP foi bloqueado. Isso **não deveria acontecer**: com cada um preso à sua cota, a soma máxima é 280 de 300.

**2. O que o servidor sabe / não sabe:** Sabe que o IP bloqueou. Não consegue avisar o outro. Não sabe a causa (terceiro sistema no mesmo IP? números furados? virada de janela mal detectada? alguém fora da regra?).

**3. Lógica do balde:**
- **Tirar:** Nada, de nenhum balde. Congela.
- **Atualizar:** Estado "penalizado" no balde local, com a informação da resposta. O outro vai congelar sozinho quando tomar o 429 dele — leva no máximo mais ou menos 1 chamada. Depois da liberação: além da primeira janela extra-conservadora, este servidor **reduz a própria cota** nas janelas seguintes (por exemplo, para a metade) até a causa ser entendida — se a folga furou uma vez, ela estava mal dimensionada.
- **Fonte da verdade:** A resposta de bloqueio.

**4. Risco:** As tentativas do outro durante o congelamento esticarem a pena para os dois (sem comunicação, sem aviso); e a causa-raiz continuar ativa na retomada.

**5. Comportamento esperado:** Freio local imediato; volta humilde (metade da cota); e o "429 no modo sem comunicação" tratado como **alarme de incidente**, nunca como rotina.

### Cenário 26 — Timeout

**1. Situação:** Sem comunicação, uma chamada deste servidor morreu sem resposta.

**2. O que o servidor sabe / não sabe:** Não sabe se a API contou a chamada. O outro servidor não fica sabendo da incerteza — irrelevante para ele: a cota fixa o protege.

**3. Lógica do balde:**
- **Tirar:** Nada por causa do timeout; reenvio é chamada nova, dentro da cota.
- **Atualizar:** A chamada vira "**incerta**": descontada do saldo estimado local **até a janela virar**. Sem aviso a ninguém. **Regra dura da cegueira:** se as incertas acumularem (por exemplo, 5 seguidas) **sem nenhum header novo no meio**, o tráfego normal para e só saem chamadas de teste — cego **e** sozinho é a pior combinação possível.
- **Fonte da verdade:** Pessimismo até a virada.

**4. Risco:** Insistir em enviar com a leitura envelhecendo e as incertas empilhando — cada envio às cegas é uma aposta contra a premissa 9.

**5. Comportamento esperado:** Pessimismo crescente na proporção da cegueira; recuperação automática quando os headers voltarem ou a janela virar.

---

## Amarrações (garantias de consistência entre cenários)

1. **Queda de comunicação vs. cota fixa:** aplicar a cota de 140 no exato momento da queda estouraria (parte das 300 já foi gasta em conjunto). Por isso, a janela da queda usa **metade do saldo conhecido no momento da queda**, e a cota de 140 só vale da primeira virada em diante. Essa exceção está repetida dentro de cada cenário do modo sem comunicação (7 a 12) — não é preciso ler o cenário 13 para aplicá-la.
2. **"Balde vazio" nunca é vazio de verdade:** os tetos param antes do zero real (parada em 20, reserva em 10). A chamada de teste vive na folga entre 20 e 10 — ela nunca é a 301ª chamada.
3. **Nada acontece "depois de X segundos":** chamadas de teste são puxadas por pedidos esperando e limitadas por orçamento; a virada da janela é detectada pelo header (saldo subiu = virou); a estabilidade da comunicação é medida em janelas da API. A única exceção candidata é a saída do bloqueio (pergunta em aberto 1).

## Perguntas em aberto (decisão humana antes de implementar)

1. **Saída do bloqueio:** tentar durante o bloqueio faz a pena recomeçar? Se **não**, dá para sair por chamadas de teste (100% sem relógio). Se **sim** ou na dúvida, esperar o prazo informado pela API exige um cronômetro local iniciado pelo valor que ela deu — seria a única exceção à regra "relógio local não vale", e precisa ser aprovada explicitamente.
2. **Saldo pós-bloqueio:** quando a API libera, a janela volta cheia, parcial ou zerada? Isso define quão agressiva pode ser a retomada.
3. **Destino dos pedidos internos durante a espera** (ponto de parada ou bloqueio): fila com espera? Erro imediato "tente depois"? Tempo máximo de fila? Decisão de produto.
4. **Semântica exata dos headers:** existe identificador de janela / instante de zerar no relógio da API? O saldo reflete o instante da resposta? A regra "saldo subiu = janela virou" depende dessas garantias.
5. **Calibração dos números** (parada 20, reserva 10, cota 140, regra dos 5, 5 testes): os princípios estão definidos; os valores precisam de ajuste com o tráfego real e o custo aceitável de capacidade.
6. **Erros que não são 429** (outros 4xx/5xx): contam no limite da API? Hoje o desenho assume que sim (pessimismo); se não contarem, dá para devolver a ficha.
7. **Detecção do silêncio por eventos:** quantos movimentos sem confirmação do outro definem "parei de ouvir"? Caso extremo: sem tráfego, a queda demora a ser percebida — inofensivo para o gasto (ninguém está gastando), mas atrasa a reunificação. Aceitável?
8. **Terceiro sistema no mesmo IP:** todo o desenho assume só A e B. Um 429 no modo sem comunicação é o alarme; vale monitorar continuamente "saldo do header vs. gasto esperado dos dois" para detectar cedo.
