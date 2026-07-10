Contexto: Você tem acesso ao código deste projeto (ms-outbound-mumselic), aos artefatos 00-reconhecimento.md, val-*.md, workflow-fase-A-extracao.md, aos resultados dos passos 01–05, e aos resultados das rodadas de bootstrap e do fluxo feliz que você já produziu — use-os como base e referencie. Já está confirmado por leitura que (a) a ordem dos três gates é a que o fluxo feliz cravou e (b) os 4 endpoints compartilham o mesmo fluxo; portanto narre os cenários UMA vez, valendo para os quatro. Não invente: todo fato vem do código que você lê. Onde não encontrar, escreva "NÃO ENCONTRADO". Não atribua significado de negócio que o código não comprove. Cite arquivo e linha em cada afirmação técnica. Mascarar segredos.
Quem vai ler: um engenheiro sênior, mas enferrujado e novo em Java/Spring Boot. Quero aprender, não só receber fatos.
REGRA DE OURO DO FORMATO — toda afirmação técnica vem colada à sua tradução em linguagem simples, no mesmo passo:

[o que o código faz, com classe/método/linha] — (em miúdos: [o que significa, por que existe, o que aconteceria sem isso, com analogia se ajudar])

Se um trecho não tiver o "em miúdos", está incompleto. Prefiro longo e mastigado a curto e técnico.
O que eu quero nesta rodada: o relato narrado de TODOS os caminhos de falha/rejeição que existirem no código — não só os que eu listar. Vasculhe o fluxo e encontre todo ponto onde uma requisição pode ser barrada, rejeitada, ou tratada diferente do caminho feliz: validações, exceções capturadas, retornos de erro de chamadas externas, falhas de infraestrutura (banco, token-cache), entradas malformadas, etc.
Eu já conheço os quatro abaixo — eles são PONTO DE PARTIDA, não a lista completa. Sua tarefa é cobri-los E descobrir os que eu não listei. Marque claramente quais cenários estão ALÉM da minha lista (ex: "⚠️ ALÉM DA LISTA INFORMADA").
Cenários que já conheço (cubra na ordem real dos gates):

Token interno inválido — primeiro gate. Como o TokenCacheClient responde, o que a app faz com isso.
Schema inválido (corpo malformado/fora do contrato SELIC) — assumindo token válido. Qual schema é aplicado, como a falha é detectada (json-schema-validator), o que volta.
Duplicata (transactionID já existente) — assumindo token e schema válidos. Como a duplicata é detectada (consulta à TMSCREQUEST_STATUS?), e o desfecho.
401 do SELIC (sub-bloco à parte: não é gate de entrada, ocorre na borda externa depois que tudo passou). A requisição foi encaminhada ao SELIC e voltou 401. O que a app faz diferente do caminho feliz? Refaz o token e tenta de novo, ou propaga o erro?

Procure ativamente, no mínimo, por (e qualquer outro que o código revelar): timeout ou indisponibilidade nas chamadas externas; outros códigos de erro do SELIC além de 401 (400, 403, 500, etc.); falha ao obter o token de saída no TokenCacheClient; corpo vazio ou content-type incorreto; transactionID ausente ou malformado; banco/H2 indisponível na consulta ou na gravação; qualquer exceção tratada por @ExceptionHandler/@ControllerAdvice global.
Para CADA cenário (os meus e os que você achar): onde exatamente o fluxo para, por quê, o que grava em TMSCREQUEST_STATUS (qual IND_STATUS), o que o cliente recebe (status HTTP + corpo), onde a thread morre, e deixe explícito o que "sobe" (continua) e o que "não sobe" (interrompe).
Três faixas costuradas em cada cenário: (1) Negócio — só até onde o código autorizar; (2) Técnico/baixo nível; (3) Código (classe/método/linha). Sobre todas, a regra de ouro do "em miúdos".
Ao final, três listas: (a) [OK] confirmado pelo código; (b) [SUP — verificar no debug]; (c) [NÃO ENCONTRADO]. E uma nota final: algum cenário que você só conseguiria confirmar de verdade rodando (debug), não só lendo?

# Resiliência e Premissas – Cenários do Rate Limiting Distribuído no selic-conecta-outbound

Este documento explica, em linguagem simples, cada cenário comportamental do proxy de saída (instância do **selic-conecta-outbound**) que encaminha requisições para a **API SELIC CONECTA**. O foco está em como cada situação afeta a **resiliência** do serviço e quais **premissas** são essenciais para manter o sistema funcionando sem bloqueios.

Existem dois modos principais de operação:
- **Coordenado:** as duas instâncias conseguem se comunicar e compartilham informações de consumo. Decisões são tomadas em conjunto.
- **Descoordenado:** a comunicação entre as instâncias falhou. Cada uma decide sozinha, usando apenas suas informações locais e uma cota segura (um “balde” individual).

Há ainda o **modo Bloqueado**, que se sobrepõe aos outros dois quando a API SELIC CONECTA responde com HTTP 429 (limite estourado), obrigando o sistema a parar de enviar requisições temporariamente.

As regras de ouro que garantem a resiliência são:
- **Segurança > vazão:** evitar o bloqueio é mais importante do que encaminhar o máximo possível de requisições.
- **Sem fila:** requisições que não podem ser atendidas no momento são recusadas imediatamente (erro genérico). Nada fica parado esperando.
- **Disponibilidade para recusar:** mesmo bloqueado ou sem comunicação, o serviço continua respondendo (recusando) as chamadas.
- **Log de tudo:** qualquer evento anormal (perda de comunicação, falta de cabeçalhos, bloqueio, mudança de limite) é registrado para auditoria.

---

## Cenário 1.1.1 – Primeira requisição com comunicação ativa e sem dados reais
- **Modo:** Coordenado (instâncias se comunicam)
- **Problema:** O sistema acabou de iniciar e nunca recebeu uma resposta da API SELIC CONECTA com os cabeçalhos de rate limit. Só temos o valor documental de 300 requisições por minuto.
- **Impacto na resiliência:** A decisão de encaminhar é baseada no contador compartilhado. Enquanto as duas instâncias dividem o mesmo contador, a soma nunca ultrapassará 300. Se a comunicação falhar nesse momento, a transição para o modo descoordenado precisa ser cuidadosa para não estourar o limite (tratada em 1.4.1).
- **Premissas:** O valor de 300 é apenas uma referência, será substituído assim que a primeira resposta real chegar. A comunicação entre as instâncias está ativa e confiável.

---

## Cenário 1.2.1 – Requisição encaminhável dentro do consumo conjunto
- **Modo:** Coordenado
- **Problema:** Situação normal: o contador compartilhado indica que ainda há espaço no limite global.
- **Impacto na resiliência:** Enquanto a comunicação estiver ativa, o sistema aproveita ao máximo a cota, respeitando o limite. A resiliência vem do fato de as duas instâncias agirem como uma só, evitando surpresas.
- **Premissas:** O contador compartilhado reflete fielmente o consumo de ambas as instâncias. A comunicação é rápida o suficiente para que o contador nunca fique desatualizado a ponto de causar excesso.

---

## Cenário 1.2.2 – Atualização de estado a partir de resposta 2xx com cabeçalhos
- **Modo:** Coordenado
- **Problema:** A API devolve os cabeçalhos `RateLimit-Limit`, `RateLimit-Remaining` e `RateLimit-Reset`. Precisamos incorporar essas informações ao estado compartilhado.
- **Impacto na resiliência:** Atualizar o limite real (`L`) e o contador restante evita que o sistema continue usando um valor desatualizado. A sincronização imediata com a outra instância garante que ambas tomem decisões com a mesma informação, prevenindo divergências perigosas.
- **Premissas:** A comunicação entre instâncias permite propagar rapidamente esses valores. O valor `RateLimit-Remaining` é confiável no modo coordenado, pois o consumo é conjunto.

---

## Cenário 1.2.3 – Requisição recusada por consumo conjunto no limite
- **Modo:** Coordenado
- **Problema:** O contador compartilhado já atingiu o limite `L`. Uma nova requisição chega e precisa ser barrada.
- **Impacto na resiliência:** Essa recusa imediata é a materialização da regra “segurança > vazão”. O sistema não tenta forçar o encaminhamento, evitando o bloqueio. A disponibilidade é mantida: a origem recebe um erro genérico, mas o serviço não fica indisponível.
- **Premissas:** O contador compartilhado é preciso. Não há fila – a requisição é descartada sem retenção.

---

## Cenário 1.3.1 – Redução de limite informada (folga suficiente)
- **Modo:** Coordenado
- **Problema:** A API SELIC CONECTA diminuiu o limite por janela (`L` menor), mas o consumo acumulado até agora ainda cabe no novo teto.
- **Impacto na resiliência:** O sistema se adapta imediatamente, reduzindo o encaminhamento futuro. A resiliência é mantida porque a comunicação permite coordenar essa mudança sem sustos. Se a folga não fosse suficiente, cairíamos no cenário 1.3.2.
- **Premissas:** A nova informação chega por cabeçalhos e é compartilhada instantaneamente. O contador compartilhado está correto.

---

## Cenário 1.3.2 – Redução de limite abaixo do consumo já realizado
- **Modo:** Coordenado, com risco de transição para Bloqueado
- **Problema:** O novo limite é menor do que o número de requisições que o conjunto já enviou na janela atual. Já ultrapassamos o novo `L`, mesmo sem querer.
- **Impacto na resiliência:** É uma situação inevitável (a redução foi maior do que a folga). O sistema cessa novos envios imediatamente, o que impede que o estouro aumente. Se a API retornar 429, entramos em bloqueio. A resiliência está em tratar o bloqueio sem travar o serviço e em não tentar encaminhar mais nada, limitando o dano.
- **Premissas:** A redução foi comunicada pelos cabeçalhos. O bloqueio resultante é tratado como “inevitável” e não conta como falha do sistema.

---

## Cenário 1.3.3 – Resposta da API sem cabeçalhos de rate limit
- **Modo:** Coordenado (mas vale para qualquer modo)
- **Problema:** A API responde com erro 500, ou um 200 sem os cabeçalhos esperados. Não temos atualização da cota.
- **Impacto na resiliência:** O sistema mantém os últimos valores válidos de limite e restante. Não toma decisões baseadas em informação corrompida. O registro em log permite diagnosticar se a API está com problemas frequentes. A resiliência é garantida porque o “semáforo” continua no último estado confiável.
- **Premissas:** A ausência de cabeçalhos não significa renovação de cota. O sistema é conservador e não assume nada.

---

## Cenário 1.3.4 – Detecção de HTTP 429 em modo coordenado
- **Modo:** Transição para Bloqueado
- **Problema:** Recebemos um 429. A API SELIC CONECTA bloqueou nossa identidade.
- **Impacto na resiliência:** Imediatamente paramos de enviar qualquer requisição. A prioridade é não agravar o bloqueio. O serviço continua disponível para recusar chamadas. O evento é logado, incluindo a causa aparente. A resiliência está na reação rápida e na capacidade de se recuperar após o tempo de bloqueio.
- **Premissas:** O bloqueio é por janela, e os cabeçalhos `RateLimit-Reset`/`Retry-After` indicam por quanto tempo. O relógio local pode divergir, mas a interpretação conservadora evita envio prematuro.

---

## Cenário 1.4.1 – Perda de comunicação durante operação coordenada
- **Modo:** Transição de Coordenado para Descoordenado
- **Problema:** A comunicação entre as instâncias caiu (queda de uma delas, partição de rede, etc.). O contador compartilhado deixa de ser confiável.
- **Impacto na resiliência:** Essa transição é o principal desafio de resiliência do sistema. Para garantir que a soma das duas instâncias não estoure o limite, cada uma abandona o contador conjunto e passa a usar apenas sua cota local (baseada no último `L` conhecido). O dimensionamento da cota local deve ser conservador para manter a segurança. A transição é automática e não deve causar bloqueio – mas exige que a cota local seja pequena o suficiente para que a soma das duas ainda fique abaixo de `L`.
- **Premissas:** A detecção de falha de comunicação é confiável. Cada instância consegue calcular uma `cota_local` segura (fração de `L`) que, mesmo se a outra também usar, a soma não ultrapasse o limite global.

---

## Cenário 1.5.1 – Aumento de limite informado
- **Modo:** Coordenado
- **Problema:** A API aumentou o `L`. Temos mais espaço.
- **Impacto na resiliência:** A comunicação permite que ambas as instâncias aumentem o encaminhamento de forma coordenada, aproveitando a folga extra sem risco.
- **Premissas:** O novo limite é confiável e a comunicação permanece ativa.

---

## Cenário 1.5.2 – Renovação da janela da API (reset)
- **Modo:** Coordenado
- **Problema:** A janela de 60 segundos da API virou. Os cabeçalhos indicam que o `RateLimit-Remaining` voltou a um valor alto.
- **Impacto na resiliência:** O sistema zera (ou corrige) o contador compartilhado com base nos cabeçalhos, sem confiar no relógio local. Isso evita que se perca a oportunidade de encaminhar na nova janela ou, pior, que se continue encaminhando como se ainda houvesse folga quando na verdade o contador deveria ter sido resetado.
- **Premissas:** Não sabemos o instante exato da virada; confiamos nos cabeçalhos para identificar o reset. O relógio local não é usado como autoridade.

---

## Cenário 2.1.1 – Startup sem comunicação e sem dados reais
- **Modo:** Descoordenado (instâncias não se comunicam)
- **Problema:** Acabamos de iniciar, não temos comunicação com a outra instância nem recebemos nenhuma resposta real da API. Só temos o valor documental de 300.
- **Impacto na resiliência:** Cada instância adota uma `cota_local` conservadora (ex.: 40% de 300) e passa a contar apenas suas próprias requisições. A soma das duas cotas é ≤ 300, garantindo que, mesmo no pior caso, o limite global não será excedido. A vazão fica reduzida, mas o sistema não corre risco de bloqueio.
- **Premissas:** A cota local precisa ser dimensionada para que a soma das duas (cada uma usando sua cota) nunca ultrapasse `L`. O valor documental de 300 é usado apenas até a primeira resposta com cabeçalhos.

---

## Cenário 2.2.1 – Requisição dentro da cota local (modo descoordenado)
- **Modo:** Descoordenado
- **Problema:** Situação normal no modo descoordenado. O contador local ainda não atingiu a `cota_local`.
- **Impacto na resiliência:** O encaminhamento ocorre porque a instância está segura de que, mesmo que a outra também tenha enviado suas próprias requisições, a soma permanecerá dentro do limite. A cota local foi projetada para isso.
- **Premissas:** A cota local é uma fração de `L` robusta à incerteza temporal (janelas desalinhadas). O `RateLimit-Remaining` vindo da API não é usado como autorização, apenas como informação extra.

---

## Cenário 2.2.2 – Requisição excede a cota local
- **Modo:** Descoordenado
- **Problema:** A instância já consumiu toda a sua cota local. Uma nova requisição é recusada.
- **Impacto na resiliência:** Essa recusa é essencial para a segurança. Mesmo que a API ainda informe `RateLimit-Remaining > 0`, não podemos confiar que essa folga é nossa – a outra instância pode estar consumindo simultaneamente. Recusar evita o bloqueio. O serviço continua disponível para responder com erro.
- **Premissas:** `RateLimit-Remaining` não é vinculante. A cota local é a única autoridade para encaminhamento no modo descoordenado.

---

## Cenário 2.2.3 – Atualização de `L` e recálculo da cota a partir de resposta com cabeçalhos
- **Modo:** Descoordenado
- **Problema:** A API nos manda um novo `L` (ou o primeiro valor real). Precisamos recalcular a `cota_local` com base nesse novo limite.
- **Impacto na resiliência:** A cota local se ajusta imediatamente ao limite real, mantendo a margem de segurança. Se o novo limite for menor, a cota diminui; se for maior, podemos aumentar a vazão, sempre respeitando a regra da soma ≤ `L`.
- **Premissas:** O recálculo usa a mesma fração conservadora definida na solução (ex.: 40%). Se a nova cota for menor que o `contador_local` atual, a instância para de encaminhar (cenário 2.3.2).

---

## Cenário 2.3.1 – Redução de limite (recálculo da cota para baixo, com folga)
- **Modo:** Descoordenado
- **Problema:** O limite `L` diminuiu, mas o contador local ainda está abaixo da nova cota.
- **Impacto na resiliência:** O sistema se adapta reduzindo o encaminhamento futuro. A segurança é mantida porque a cota local foi recalculada com a mesma margem conservadora. Se não houvesse folga, o próximo cenário se aplicaria.
- **Premissas:** A comunicação permanece inativa. O ajuste é imediato com base nos cabeçalhos.

---

## Cenário 2.3.2 – Redução de limite abaixo do já consumido localmente
- **Modo:** Descoordenado (pode levar a Bloqueado)
- **Problema:** O novo `L` é tão pequeno que a cota local recalculada fica abaixo do `contador_local` atual. Já consumimos mais do que a nova cota permitiria.
- **Impacto na resiliência:** A instância cessa imediatamente qualquer novo envio. Um bloqueio (429) pode ser inevitável, mas o sistema faz a sua parte: para de agravar a situação. A resiliência está em limitar o dano e tratar o bloqueio de forma controlada.
- **Premissas:** A cota local é a referência; se foi ultrapassada, paramos. A eventual resposta 429 leva ao Modo 3.

---

## Cenário 2.3.3 – Detecção de HTTP 429 em modo descoordenado
- **Modo:** Transição para Bloqueado
- **Problema:** Mesmo no modo descoordenado, a API pode responder 429 se a soma das duas instâncias (ambas seguindo suas cotas) ainda assim excedeu o limite, ou por qualquer outro motivo.
- **Impacto na resiliência:** A reação é a mesma: bloqueio imediato, recusa de novas requisições, log do evento. O fato de estar descoordenado não muda o tratamento do bloqueio.
- **Premissas:** O 429 é tratado igual em qualquer modo. A cota local pode não ter sido suficiente para evitar o bloqueio (por exemplo, se a outra instância violou as regras ou se houve condição de corrida), mas isso é registrado para análise.

---

## Cenário 2.3.4 – Resposta sem cabeçalhos de rate limit (descoordenado)
- **Modo:** Descoordenado
- **Problema:** Similar ao 1.3.3, mas sem comunicação com o par. A resposta veio sem os cabeçalhos de cota.
- **Impacto na resiliência:** Mantemos `L` e `cota_local` inalterados. Nenhuma decisão é tomada com informação faltante. A resiliência está na conservação do estado anterior.
- **Premissas:** A ausência de cabeçalhos não é interpretada como renovação de janela. O log permite auditoria.

---

## Cenário 2.4.1 – Restabelecimento da comunicação entre instâncias
- **Modo:** Transição de Descoordenado para Coordenado
- **Problema:** A comunicação voltou. Agora as duas instâncias precisam voltar a compartilhar o estado de consumo sem correr o risco de, juntas, ultrapassarem o limite.
- **Impacto na resiliência:** Essa transição é crítica. É preciso sincronizar os contadores locais: cada instância informa à outra quantas requisições já enviou. O novo contador compartilhado é a soma dos dois. Se essa soma já tiver atingido `L`, o sistema não encaminha mais nada até a próxima janela. A resiliência está em não causar uma violação durante a reconciliação – a soma é respeitada, e o sistema volta a operar coordenado com segurança.
- **Premissas:** O mecanismo de sincronização é confiável. A soma dos contadores pode revelar que o limite já foi atingido, e nesse caso a vazão cai a zero temporariamente, mas o bloqueio é evitado.

---

## Cenário 2.4.2 – Instância retorna após queda, comunicação ainda inativa
- **Modo:** Descoordenado
- **Problema:** Uma instância que havia caído voltou a funcionar, mas a comunicação com a outra ainda não foi restabelecida. Ela não sabe quantas requisições a outra processou durante sua ausência.
- **Impacto na resiliência:** A instância renascida adota o modo descoordenado: zera seu contador local e aplica a cota local com base no último `L` conhecido (ou 300). Isso garante que, mesmo sem informação do passado, a soma das duas instâncias (a outra ainda em operação) continuará dentro do limite, pois cada uma se limita à sua cota segura. A resiliência está em não presumir nada sobre o consumo da outra.
- **Premissas:** O contador local é zerado na reinicialização (a menos que haja persistência, mas o modo conservador não depende disso). O `L` usado pode ser o documental se não houver cache do último valor real.

---

## Cenário 2.4.3 – Instância retorna e comunicação é restabelecida
- **Modo:** Transição para Coordenado após queda
- **Problema:** A instância renascida agora consegue falar com a outra. É o mesmo que o 2.4.1, mas com uma instância tendo seu contador zerado.
- **Impacto na resiliência:** As duas instâncias sincronizam: a que nunca caiu informa seu contador local; a que voltou informa zero. O consumo compartilhado será o contador da que ficou ativa. Se esse valor for menor que `L`, podem voltar a encaminhar coordenadamente. A resiliência está em garantir que a transição não cause excesso, mesmo com a assimetria de informações.
- **Premissas:** A comunicação permite a troca de contadores. A soma adotada é o consumo real da que permaneceu ativa, que é o pior caso.

---

## Cenário 2.5.1 – Incerteza temporal entre janelas locais e a janela da API
- **Modo:** Descoordenado (é o cenário que desafia a premissa de janela)
- **Problema:** No modo descoordenado, cada instância controla seu próprio ritmo. Porém, nenhuma sabe exatamente quando a janela de 60 segundos da API SELIC CONECTA começa ou termina. Os relógios das instâncias podem divergir entre si e do relógio da API. Assim, se cada uma simplesmente dividisse o limite ao meio (ex.: 150 cada) e resetasse seu contador a cada 60 segundos pelo próprio relógio, poderia acontecer de as duas enviarem 150 requisições quase ao mesmo tempo, mas que caíssem dentro de uma mesma janela da API, totalizando 300 – o que é o limite, mas com risco de ultrapassagem se houver desalinhamento. Pior: se a janela local de uma instância começar um pouco antes do reset da API, ela pode consumir 150 no fim da janela anterior da API e mais 150 no início da próxima, tudo dentro de uma mesma janela real de 60s, estourando o limite.
- **Impacto na resiliência:** Este é o ponto mais sensível para a resiliência no modo descoordenado. Para ser verdadeiramente resiliente, a cota local não pode ser apenas uma fração fixa de `L` renovada periodicamente; ela precisa incorporar uma margem de segurança que compense a incerteza temporal. Por exemplo, limitar cada instância a 40% de `L`, com um mecanismo de janela deslizante que impeça rajadas. Isso faz com que, mesmo na pior combinação de janelas sobrepostas, a soma nunca exceda `L`. A vazão será menor, mas o bloqueio é evitado.
- **Premissas:** Os relógios são independentes e não confiáveis para sincronização. A solução precisa ser robusta a esse desalinhamento. A fração da cota local e a política de renovação são definidas pelo projeto, sempre com foco em segurança.

---

## Cenário 2.5.2 – Persistência em cold start sob comunicação inativa
- **Modo:** Descoordenado
- **Problema:** O sistema está em modo descoordenado desde o início e a API ainda não devolveu nenhum cabeçalho com o limite real. Continuamos usando o valor documental de 300.
- **Impacto na resiliência:** Enquanto não chega a informação real, a cota local conservadora derivada de 300 mantém a segurança. A resiliência está em não “chutar” um limite maior e em registrar a ausência de cabeçalhos para que se possa investigar.
- **Premissas:** O valor documental de 300 é apenas uma premissa inicial, não uma verdade. Assim que a primeira resposta real chegar, ele será substituído.

---

## Cenário 3.1.1 – Entrada no bloqueio ao detectar 429
- **Modo:** Bloqueado (sobrepõe qualquer modo anterior)
- **Problema:** A API SELIC CONECTA respondeu 429, sinalizando que a identidade foi bloqueada por exceder o limite.
- **Impacto na resiliência:** Imediatamente o sistema para de enviar requisições e começa a recusar todas as chamadas. Isso evita que o bloqueio se prolongue ou piore. A disponibilidade do serviço é mantida, pois as recusas são rápidas (erro genérico). O log do bloqueio é fundamental para auditoria.
- **Premissas:** O bloqueio é por janela; o tempo de espera é extraído dos cabeçalhos `RateLimit-Reset` ou `Retry-After`. A interpretação é conservadora para não reenviar antes da hora.

---

## Cenário 3.2.1 – Requisição recebida durante o bloqueio
- **Modo:** Bloqueado
- **Problema:** Chegou uma requisição enquanto o sistema está proibido de encaminhar.
- **Impacto na resiliência:** A requisição é recusada imediatamente, sem fila. O serviço continua “de pé”, recusando, em vez de ficar indisponível. Isso é fundamental para que as aplicações internas não fiquem penduradas.
- **Premissas:** A regra “sem fila” é absoluta. O tempo de bloqueio é respeitado.

---

## Cenário 3.3.1 – Fim da janela de bloqueio (reset)
- **Modo:** Saída do Bloqueado, retorno ao Coordenado ou Descoordenado
- **Problema:** O período de bloqueio expirou. O sistema pode voltar a encaminhar.
- **Impacto na resiliência:** A retomada é automática. O sistema verifica o estado da comunicação e volta ao modo adequado. Se a comunicação estiver ativa, sincroniza os contadores (que devem ser zerados, pois a janela virou) e volta ao modo coordenado. Se inativa, adota o modo descoordenado com cota local zerada. A resiliência está em não perder a oportunidade de retomar e em não causar um novo bloqueio por erro de contador.
- **Premissas:** O temporizador pode ter uma pequena imprecisão (relógio local), por isso uma margem extra é prudente. Os contadores são resetados.

---

## Cenário 3.4.1 – Mudança do estado da comunicação durante o bloqueio
- **Modo:** Bloqueado com mudança de comunicação
- **Problema:** Enquanto está bloqueado, a comunicação entre instâncias caiu ou voltou. Isso não afeta o bloqueio em si, mas definirá o modo de operação quando o bloqueio terminar.
- **Impacto na resiliência:** O sistema mantém o bloqueio até o fim. Ao sair, verifica se a comunicação está ativa ou não e adota o modo correspondente. Se precisar sincronizar, faz isso antes de liberar o primeiro envio. Essa verificação tardia garante que a transição pós-bloqueio seja segura, independentemente de instabilidades na rede durante o castigo.
- **Premissas:** A detecção de mudança de comunicação é contínua. O bloqueio tem prioridade máxima.

---

## Cenário 3.4.2 – Novo 429 após tentativa de retomada
- **Modo:** Reentrada em Bloqueado
- **Problema:** O sistema foi liberado, tentou enviar uma requisição e recebeu 429 novamente. Possivelmente a janela ainda não havia virado completamente ou houve um novo estouro.
- **Impacto na resiliência:** O ciclo de bloqueio se repete. O sistema não insiste; volta a recusar imediatamente. Esse comportamento evita “martelar” a API e sofrer penalidades maiores. A resiliência está em tratar cada 429 como um evento independente e manter a disciplina de contenção.
- **Premissas:** A interpretação do `Retry-After`/`Reset` é refeita com os novos cabeçalhos. O log registra a recorrência para diagnóstico.

---

# Resumo das Premissas de Resiliência

1. **Comunicação confiável quando ativa:** no modo coordenado, o estado compartilhado precisa ser atualizado rapidamente para evitar decisões conflitantes.
2. **Cota local conservadora:** no modo descoordenado, a fração de `L` deve ser pequena o bastante para que a soma das duas instâncias, mesmo com janelas desalinhadas, nunca ultrapasse o limite global.
3. **Desconfiança do relógio local:** nenhum reset de contador é baseado no relógio; sempre nos cabeçalhos ou, no máximo, em temporizadores com margem.
4. **Recusa imediata e sem fila:** garante que o serviço nunca fique bloqueado aguardando liberação, mantendo a disponibilidade para responder.
5. **Tratamento uniforme do 429:** independe do modo ou da causa, o bloqueio é respeitado e o sistema para de enviar.
6. **Log de anomalias:** todas as transições, ausências de cabeçalhos e bloqueios são registrados, permitindo auditoria e ajuste fino das margens de segurança.

Com essas premissas, o selic-conecta-outbound consegue operar de forma resiliente, evitando bloqueios da API SELIC CONECTA e mantendo a disponibilidade mesmo sob falhas de comunicação ou variações abruptas de limite.

# Especificação de Problema – Rate Limiting Distribuído

## 1. Contexto
Um proxy recebe requisições de sistemas internos e as encaminha a um provedor externo.  
O proxy opera em duas instâncias, em servidores separados, para alta disponibilidade.  
Os sistemas internos enxergam o proxy como um ponto único.  
O provedor externo enxerga as duas instâncias como uma única identidade de origem (IP compartilhado).  

O provedor impõe um limite de requisições por minuto a essa identidade.  
Quando o limite é ultrapassado, o provedor bloqueia toda a identidade por um tempo determinado.  

As duas instâncias possuem um mecanismo embutido de coordenação que permite compartilhar informações de consumo quando a comunicação entre elas está ativa.  
Enquanto esse mecanismo funciona, as instâncias tomam decisões considerando o consumo conjunto.  

O problema surge quando a comunicação entre as instâncias falha (por queda de uma delas, partição de rede ou qualquer causa que as impeça de trocar informações).  
Nessas situações, cada instância precisa continuar encaminhando requisições sem ter visibilidade do consumo da outra, mas a soma dos encaminhamentos ainda precisa respeitar o limite global.  

A demanda total que chega às instâncias pode, rotineiramente, superar o limite disponível.  
O desafio é conter o consumo conjunto das duas instâncias em todos os cenários (com ou sem comunicação entre elas), usando apenas os recursos embarcados, sem introduzir componentes de infraestrutura externos.

## 2. Atores Externos e suas Características
- **Sistemas Internos de Origem**  
  Enviam requisições de negócio ao proxy.  
  Cada requisição é entregue a uma única instância, nunca duplicada.  
  Não têm conhecimento da existência de duas instâncias.

- **Provedor Externo**  
  Contabiliza as requisições recebidas da identidade de origem compartilhada.  
  Impõe um limite de requisições por janela de tempo.  
  Altera esse limite quando desejar (aumentos ou reduções).  
  Informa o estado da cota nos cabeçalhos de cada resposta.  
  Em caso de violação, bloqueia temporariamente toda a identidade.

## 3. Interface e Comportamento do Provedor Externo
- O provedor utiliza janelas fixas de 60 segundos, alinhadas ao seu próprio relógio.  
- Toda resposta de sucesso (HTTP 2xx) inclui os cabeçalhos:  
  - `RateLimit-Limit`: número máximo de requisições por janela (ex.: 300).  
  - `RateLimit-Remaining`: quantas requisições ainda restam na janela atual.  
  - `RateLimit-Reset`: segundos restantes até o fim da janela e renovação do limite.  
- Quando o limite é excedido, o provedor responde com HTTP 429.
    - A resposta 429 inclui os mesmos cabeçalhos de rate limit (RateLimit-Limit, RateLimit-Remaining: 0, RateLimit-Reset), podendo conter também o cabeçalho Retry-After.
    - O corpo da resposta contém uma mensagem de erro.
- Durante o bloqueio, qualquer requisição da mesma identidade é rejeitada com 429.  
- O valor de `RateLimit-Limit` pode mudar entre uma resposta e outra (aumentar ou diminuir).  
- O bloqueio dura pelo restante da janela corrente. Após o reset, a identidade volta a ser aceita.

## 4. Informações que Chegam ao Sistema (Inputs)
- Requisições de negócio oriundas de sistemas internos, uma a uma.  
- A cada resposta do provedor:  
  - Limite total (`RateLimit-Limit`, numérico).  
  - Requisições restantes na janela atual (`RateLimit-Remaining`, numérico).  
  - Segundos para o reset da janela (`RateLimit-Reset`, numérico).  
- Quando ocorre bloqueio:  
  - Resposta HTTP 429 com `Retry-After` e `RateLimit-Remaining: 0`.  
- Antes da primeira resposta do provedor, o valor de referência documentado é 300 requisições por minuto.  
- Estado da comunicação com a outra instância: ativa ou inativa.
- Respostas do provedor que não contenham os cabeçalhos RateLimit-Limit, RateLimit-Remaining e RateLimit-Reset (por exemplo, erro HTTP 500 ou resposta 2xx sem os cabeçalhos). Nesse caso, o sistema não recebe atualização de estado da cota naquela resposta.

### 4.1. Dicionário de Informações do Provedor Externo

**Cabeçalhos presentes em respostas de sucesso (HTTP 2xx) e na resposta 429:**

| Cabeçalho | Tipo | Significado | Observações |
|:---|:---|:---|:---|
| `RateLimit-Limit` | Número inteiro | Limite máximo de requisições permitidas por janela para a identidade de origem. | Pode mudar entre respostas. Na documentação de referência, o valor inicial é 300. |
| `RateLimit-Remaining` | Número inteiro | Quantidade de requisições ainda disponíveis na janela corrente, segundo o provedor. | Decrementa a cada requisição processada pelo provedor. Chega a 0 quando o limite é atingido. |
| `RateLimit-Reset` | Número inteiro (segundos) | Tempo restante, em segundos, até o fim da janela corrente e renovação do limite. | Fornecido tanto em respostas de sucesso quanto em 429. |
| `Retry-After` (somente 429) | Data/hora ou segundos | Indica quando a identidade poderá voltar a fazer requisições. | Pode ser um timestamp ou duração em segundos. |

**Valor documental de referência:**
- Antes que qualquer resposta real do provedor seja recebida (partida a frio), o único valor disponível é o documentado: **300 requisições por minuto**, com janela de 60 segundos. Esse valor é uma premissa inicial, não uma verdade autoritativa.

**Ausência de cabeçalhos:**
- Respostas do provedor que **não contenham** um ou mais dos cabeçalhos `RateLimit-Limit`, `RateLimit-Remaining`, `RateLimit-Reset` (por exemplo, erro HTTP 500 ou resposta 2xx incompleta) **não fornecem atualização** sobre o estado da cota. O sistema deve tratar essa resposta como "sem informação de cota".

## 5. Premissas e Restrições Imutáveis
- As duas instâncias executam simultaneamente, em alta disponibilidade.  
- Cada requisição de negócio chega a exatamente uma instância; não há duplicação.  
- As instâncias não compartilham relógio. Seus relógios locais são independentes e podem divergir.  
- As instâncias possuem um mecanismo embutido de coordenação que, quando a comunicação está ativa, permite compartilhar estado de consumo e tomar decisões conjuntas.  
- Esse mecanismo de comunicação é direto entre as instâncias, sem depender de servidores externos.  
- A comunicação entre as instâncias pode falhar a qualquer momento. Cada instância é capaz de detectar se essa comunicação está ativa ou inativa.  
- Nenhum componente de infraestrutura externo adicional (banco de dados, árbitro, cache centralizado) pode ser introduzido para resolver o problema.  
- O sistema não tem controle sobre o comportamento do provedor (limite, janela, bloqueios).  
- Segurança (não sofrer bloqueio) tem prioridade absoluta sobre vazão.  
- Vazão abaixo da máxima é aceitável quando a comunicação entre instâncias está inativa, desde que não ocorra bloqueio. Nesse cenário, a solução deve buscar a maior vazão possível que ainda garanta que o bloqueio não acontecerá.
- Na partida a frio, nenhuma informação real do provedor está disponível. Até a primeira resposta com cabeçalhos de rate limit, o sistema adota o valor documental de referência (300) como única informação sobre o limite.
- No modo coordenado (comunicação ativa), as decisões são tomadas com base no estado de consumo compartilhado entre as instâncias.
- Quando a comunicação falha, cada instância passa a usar exclusivamente as informações que obteve diretamente do provedor (últimos cabeçalhos recebidos por ela) e sua própria contagem de requisições encaminhadas, sem presumir nada sobre o estado da outra.
- No modo descoordenado, cada instância não pode presumir que o RateLimit-Remaining reflete apenas o seu próprio consumo. Portanto, o simples uso desse valor como "semáforo verde" para encaminhar é insuficiente para garantir que a soma das duas não ultrapasse o limite global.
- Para atender à garantia de soma dentro do limite, é admissível que cada instância adote uma cota local segura (por exemplo, uma fração do limite total), derivada exclusivamente de informações que ela possui com certeza (como o RateLimit-Limit e seu próprio contador de requisições), ignorando o RateLimit-Remaining ou utilizando-o apenas como informação complementar não vinculativa.
- Esse mecanismo de cota local pode ser pensado como um "balde" individual, cujo tamanho é determinado de forma a assegurar que, mesmo que a outra instância também encha seu próprio balde, a soma nunca exceda o limite. A política de dimensionamento e eventual recálculo desse balde é parte da solução.
- O provedor contabiliza as requisições em janelas fixas de 60 segundos alinhadas ao seu próprio relógio, cujo instante de início é desconhecido pelas instâncias.
- Como os relógios das instâncias são independentes e podem divergir entre si e em relação ao relógio do provedor, nenhuma instância pode determinar com exatidão os limites temporais da janela corrente do provedor.
- Em decorrência disso, qualquer estratégia de limitação que dependa de janelas locais ou de divisão estática do limite sem considerar a incerteza temporal não oferece, por si só, garantia de que a soma das requisições em uma janela do provedor permanecerá dentro do limite global. A solução precisa ser robusta a essa incerteza.

## 6. Regras Comportamentais
- Em qualquer circunstância, a soma total de requisições encaminhadas ao provedor pelas duas instâncias não pode exceder o limite global da janela atual.  
- Quando a comunicação entre as instâncias está ativa, o encaminhamento de cada uma considera o consumo da outra, de modo que a soma permaneça dentro do limite.  
- Quando a comunicação entre as instâncias está inativa, cada instância limita seu próprio encaminhamento com base apenas em suas informações locais, de forma que, mesmo que a outra instância também encaminhe até seu máximo possível, o total não ultrapasse o limite global.  
- Se o provedor reduzir o limite durante a operação, o sistema ajusta seu encaminhamento para não ultrapassar o novo limite, sempre que isso for possível dados os consumos já realizados.  
- Toda requisição que não puder ser encaminhada imediatamente devido ao limite é recusada de volta ao sistema interno com erro genérico. Nenhuma requisição é retida ou enfileirada.  
- Se a identidade estiver bloqueada (HTTP 429), nenhuma requisição é encaminhada ao provedor até o fim da janela de bloqueio. Todas as requisições recebidas nesse período são recusadas com erro genérico.  
- Essa regra de bloqueio se aplica independentemente da causa do 429 (violação por falta de coordenação, redução abrupta de limite ou qualquer outro motivo).  
- As regras de contenção são válidas tanto com comunicação ativa quanto inativa.

## 7. Cenários de Falha e Comportamento Esperado
- **Perda de comunicação entre instâncias**  
  Cada instância detecta a falha.  
  Passa a limitar seu encaminhamento usando apenas informações locais, de modo que a soma máxima possível das duas ainda respeite o limite global.  
  Quando a comunicação é restabelecida, o sistema volta a considerar o consumo conjunto e a coordenar os encaminhamentos.

- **Queda de uma instância**  
  Quando uma instância cai, a remanescente opera sozinha, contendo seu encaminhamento ao limite global.
  Quando a instância caída retorna e a comunicação entre elas ainda está inativa (ou ainda não foi restabelecida), ela não possui informação sobre o consumo que ocorreu durante sua ausência.
  Enquanto a comunicação não for restabelecida, a instância que retornou trata a situação como modo descoordenado, limitando seu encaminhamento com base apenas em suas próprias informações (último estado conhecido do provedor antes da queda, se disponível, e seu próprio contador zerado).
  Se a comunicação for restabelecida, as instâncias sincronizam o estado de consumo e voltam ao modo coordenado. A transição não pode provocar violação do limite global.
  A retomada e a sincronização (ou a decisão por operar descoordenado) são registradas em log.

- **Bloqueio por HTTP 429 (violação de limite)**  
  Seja qual for a causa (erro de coordenação, redução repentina de limite não absorvida, condição de corrida não prevista), o sistema detecta a resposta 429.  
  Cessa imediatamente o envio de novas requisições ao provedor.  
  Recusa todas as requisições recebidas até o fim da janela de bloqueio indicada pelo `RateLimit-Reset` ou `Retry-After`.  
  Após o reset, retoma a operação normal (coordenada ou não, conforme estado da comunicação).

- **Redução repentina do limite pelo provedor**  
  O sistema detecta a redução pelos cabeçalhos da resposta.  
  Ajusta o encaminhamento para não ultrapassar o novo limite.  
  Se o consumo corrente já tiver ultrapassado o novo limite antes do ajuste (porque a redução foi maior que a folga existente naquele instante), um bloqueio pode ocorrer e é tratado como descrito acima.

- **Resposta do provedor sem cabeçalhos de rate limit**
  O provedor retorna uma resposta sem os cabeçalhos RateLimit-Limit, RateLimit-Remaining ou RateLimit-Reset (ex.: erro 500, ou resposta 2xx incompleta).
  O sistema não atualiza suas informações de limite, capacidade restante ou janela com base nessa resposta.
  A requisição original é tratada normalmente (encaminhada à origem se erro, processada se sucesso), mas os controles de cota permanecem com os últimos valores válidos conhecidos.
  Esse evento é registrado em log específico, contendo detalhes da resposta para diagnóstico.

## 8. Objetivos Mensuráveis
- Quando a comunicação entre instâncias está ativa, o consumo somado nunca excede o limite, independentemente da demanda.  
- Quando a comunicação está inativa, o consumo somado permanece dentro do limite, ainda que com vazão reduzida.  
- Nenhum bloqueio (HTTP 429) é causado por violação que poderia ter sido evitada com as informações disponíveis.  
- Bloqueios inevitáveis (decorrentes de redução abrupta de limite que o sistema não teve tempo de absorver) são tratados sem propagação de erros para os sistemas internos além da recusa imediata da requisição.  
- O serviço permanece disponível para recusar requisições com erro genérico mesmo durante períodos de bloqueio ou de perda de comunicação.  
- A transição entre modo coordenado e descoordenado é automática e não causa violação do limite.
- Todos os eventos anômalos (comunicação interrompida, ausência de cabeçalhos, mudanças de limite, bloqueios) são registrados em log para auditoria, com informações suficientes para diagnóstico posterior.
