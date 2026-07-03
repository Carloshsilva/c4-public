# Workflow — Fase A: Extração estática (Devin)

> DUAS REGRAS QUE GOVERNAM ESTE WORKFLOW:
> 1. NÃO INFERIR. Todo passo extrai fato do código (Devin) ou observa fato em
>    runtime (debug). Nada é afirmado de cabeça.
> 2. PARAR NA DEPENDÊNCIA. O workflow só escreve passos que NÃO dependem do
>    resultado de um passo anterior. A fase de debug (Fase B) só será montada
>    DEPOIS que estes passos voltarem, porque ela depende do que eles revelarem.
>
> POR QUE 5 PASSOS (e não 7, nem 2): num projeto pequeno (26 arquivos), juntei os
> passos que são a MESMA preocupação. Mas mantive separados os traços profundos
> ("liste cada if, cite cada linha"), porque prompt gordo demais faz o Devin
> RESUMIR e TRUNCAR — o que a gente quer evitar. Os 5 são independentes entre si:
> rode em qualquer ordem.
>
> REGRAS PARA O DEVIN EM TODOS OS PROMPTS:
> - Só LER e gerar markdown. NUNCA alterar, criar ou apagar código.
> - NÃO inferir. Se não achar, escrever "NÃO ENCONTRADO". Nunca inventar.
> - NÃO resumir, NÃO interpretar. Listar fato por fato, com arquivo e linha.
> - Mascarar senha/secret/token (valor por ***MASCARADO***, manter o nome).

---

## PASSO 1 — Contrato de entrada: envelope + papel dos schemas

> Este passo responde DUAS coisas juntas porque são a mesma preocupação ("o que
> entra e como a forma dele é validada"). A parte de schema é o centro: descobrir
> COMO o sistema resolve a validação por schema, não supor.

```
Leia analise-sistema/00-reconhecimento.md para se localizar. Não altere nenhum arquivo.

PARTE A — Estrutura do envelope:
1. Localize as classes de modelo de request (pasta model/v1/request/ e subpastas, ou o tipo do @RequestBody nos controllers). Para CADA classe: nome, caminho, e cada campo (nome literal, tipo, e TODAS as anotações: @NotNull, @JsonProperty com o nome JSON, @Valid, etc). Desça nos objetos aninhados.
2. Liste, sem classificar, os campos cujo nome contenha "transac", "callback", "rgt" ou indique identificador/controle — só o nome e onde aparece.
3. Transcreva qualquer comentário literal ao lado de campos.

PARTE B — Papel e mecânica dos schemas (INVESTIGUE A FUNDO):
4. Liste TODOS os arquivos .json de schema do projeto (resources/schemas/, properties/common/schemas/ e qualquer outra pasta schemas): nome, caminho, contagem.
5. Para cada schema, liste os campos "required" do nível raiz e os tipos de topo (não cole o arquivo inteiro).
6. Encontre no código COMO o sistema decide QUAL schema usar para cada requisição/operação. Procure: um mapa/registro no código, uma resolução por nome de arquivo, uma configuração que aponta schema->operação. Reporte a classe, método e linha onde essa decisão acontece. Se não conseguir determinar, escreva "NÃO ENCONTRADO" — não adivinhe.
7. Encontre ONDE os schemas são CARREGADOS (uso de com.networknt / JsonSchemaFactory ou similar) e ONDE a validação é DISPARADA no fluxo: classe, método, linha.
8. Reporte o que a validação RETORNA e o que o código faz com erros de schema (o que é montado e devolvido ao cliente quando falta um campo).
9. Existe schema também para a RESPOSTA (validação do retorno do SELIC)? Se sim, reporte onde e como é usado.

Cite arquivo e linha em tudo. NÃO resuma. Salve em analise-sistema/passo-01-entrada-e-schemas.md.
```

---

## PASSO 2 — Trace ponta a ponta dos 4 endpoints (ordem real + persistência)

```
Leia analise-sistema/00-reconhecimento.md. Não altere nenhum arquivo.

Para CADA um dos 4 endpoints POST do OutboundControllerV1, mapeie a sequência de execução NA ORDEM EXATA do código. Liste, numerado, do início ao fim:
- cada validação (e qual condição faz rejeitar/lançar exceção) — quero ver QUAL valida primeiro: token interno, schema, ou duplicata
- cada chamada a Service (classe e método)
- cada chamada ao repositório (leitura/escrita, qual entidade)
- cada chamada externa (token-cache, SELIC) e em que ponto da ordem ocorre
- cada gravação/atualização de status no banco, em que ponto, e QUAL status é gravado — diga se é ANTES ou DEPOIS da chamada ao SELIC
- o que é retornado ao cliente no caminho de sucesso

Inclua também:
- o ponto da checagem de duplicata (leitura por transactionID): classe, método, linha, e o que acontece se já existe vs se não existe
- onde a transação @Transactional começa e termina

Mostre a cadeia como Controller.metodo() -> Service.metodo() -> Repository.metodo(). Cite arquivo e linha de cada passo. NÃO resuma — liste cada if e cada chamada na ordem real.

Salve em analise-sistema/passo-02-trace-e-persistencia.md.
```

---

## PASSO 3 — O que cada um dos três parsers faz

```
Leia analise-sistema/00-reconhecimento.md. Não altere nenhum arquivo.

Documente a lógica real de MessageControlParser, AdditionalInformationParser e CallbackEndpointExtractor. Para CADA um, e para CADA método público:
- nome do método, o que recebe (parâmetros e tipos), o que retorna
- passo a passo interno: quais campos do input lê, quais transformações aplica, quais validações faz (e o que dispara erro)
- cada if/branch/condição, com a condição literal
- qualquer valor de configuração que consulta (@Value, propriedades — ex: os flags force-integer, get-value-from-body-text) e como esse valor muda o comportamento

Cite arquivo e linha. NÃO resuma — quero cada decisão do código.

Salve em analise-sistema/passo-03-parsers.md.
```

---

## PASSO 4 — TokenCacheClient: onde é chamado e o efeito de cada retorno

```
Leia analise-sistema/00-reconhecimento.md. Não altere nenhum arquivo.

Mapeie o TokenCacheClient como ponto de decisão:
1. Encontre TODOS os lugares onde TokenCacheClient é chamado (classe, método, linha) e qual método dele é chamado em cada ponto.
2. Para CADA chamada, o que o código faz IMEDIATAMENTE DEPOIS com o retorno: todos os if sobre status/resposta (tratamento de 401, token inválido, erro, sucesso). Para cada ramo: o fluxo continua ou para? lança exceção? grava qual status no banco? o que retorna ao cliente?
3. Dentro do próprio TokenCacheClient: quais métodos existem, qual endpoint externo cada um chama (validar token interno vs obter token do SELIC), e o que retorna em sucesso e em falha.

Cite arquivo e linha. NÃO resuma — quero cada ramo de cada decisão.

Salve em analise-sistema/passo-04-tokencache.md.
```

---

## PASSO 5 — Estados, erros e desfechos ao cliente

```
Leia analise-sistema/00-reconhecimento.md. Não altere nenhum arquivo.

1. Abra RequestProcessingStatus e liste TODOS os valores possíveis (o conjunto de estados), com o nome literal de cada um.
2. Liste as classes da pasta exceptions/ (nome, caminho) e o que cada exceção representa.
3. Encontre qualquer @ControllerAdvice / @ExceptionHandler global. Para cada tipo de exceção tratado: qual status HTTP e qual corpo de resposta o cliente recebe.
4. Para cada exceção customizada, onde ela é lançada (classe, método, linha) e qual condição a dispara.
5. O que acontece em erros de chamada externa (token-cache indisponível, SELIC com erro ou 401, timeout): qual exceção/fluxo é acionado, qual status grava no banco, e o que volta pro cliente.

Cite arquivo e linha. NÃO resuma.

Salve em analise-sistema/passo-05-estados-e-erros.md.
```

---

## ===== PARADA DO WORKFLOW =====

Aqui o workflow PARA de propósito.

A Fase B (debug em runtime) depende das "fotos" que estes 5 passos vão gerar — a
forma real do envelope, a mecânica real de resolução de schema (passo 1), a ORDEM
real dos gates (passo 2), os nomes/linhas reais das classes. Escrever a Fase B
agora seria inferir esses resultados, o que quebra a regra nº 1.

Rode os 5 passos (em qualquer ordem) e me traga os resultados — arquivos ou fotos,
parciais servem. Com os fatos na mão eu monto a Fase B em cima do que de verdade
apareceu: o guia de breakpoints com classe/método/linha reais e os experimentos
de JMeter desenhados pra acender cada caminho (duplicata, schema inválido, token
inválido, 401 do SELIC).

Postura da Fase B: o debug é de DESCOBERTA, não de confirmação. Em cada ponto a
gente pergunta: (a) o que eu esperava? bateu? (b) o que aparece aqui que eu NÃO
esperava? (c) reconheço como regra, ou é desconhecido e vira pergunta pra chefe /
swagger do SELIC?
