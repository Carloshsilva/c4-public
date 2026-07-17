# Plano para estabilizar a develop

> **Convenção:** onde aparecer `<sua-branch>`, troque pelo nome da sua branch com a implementação final correta (ex.: `feature/minha-alteracao`).
> Onde aparecer `<colega>`, troque pelo nome ou email exato do colega no git (você descobre no passo 0.3).
> Onde aparecer `<COMANDO-DE-TESTE-DO-CI>`, troque pelo mesmo comando que o CI do GitHub roda (está no `.github/workflows/*.yml`, no job que trava o deploy).

**Contexto desta situação:**
- Você refez a sua implementação **do zero** na `<sua-branch>` (com rebase). Não há trabalho legítimo seu a resgatar da develop — a sua branch é a fonte de verdade do seu domínio.
- A develop está contaminada: tem o seu lixo antigo (classes que serão deletadas, arquivos que você refez), o trabalho legítimo do colega, e arquivos compartilhados (ex.: classes de teste) que você refez e que o colega **pode** ter mexido também.
- Objetivo: produzir uma branch limpa (sua versão + trabalho do colega, sem lixo) e entregá-la por PR na develop protegida.

**Regras de ouro (o que te queimou antes):**
- Nunca dê `force-push` em nada compartilhado.
- Nunca faça merge local direto na develop — ela é protegida, entra só por PR.
- Combine com o colega de **não empurrar nada pra develop** até seu PR entrar. Se ele empurrar, o alvo se move e você refaz a partir do passo 4.
- O juiz final é a suíte de testes rodando localmente (passo 7). Enquanto vermelho, você não terminou.
- **Rode tudo na MESMA sessão de terminal** — a variável `$MB` só sobrevive na sessão onde foi definida.

---

## Fase 0 — Reconhecimento e auditoria (antes de mesclar)

Objetivo: substituir a sua memória por listas objetivas. Você vai descobrir o que o colega mexeu, e quais arquivos você refez precisam de conferência (porque o colega também mexeu neles).

### 0.1 — Sincronize e ache o PONTO DE CRIAÇÃO ORIGINAL da branch

Por causa do rebase, o `git merge-base` pode falhar (retornar vazio ou apontar pra um ponto depois das suas alterações). Por isso, se ele falhar, achamos o ponto original pelo **reflog**, que sobrevive ao rebase.

```bash
git fetch origin
```

Primeiro tente o `merge-base` (caminho fácil, quando funciona):

```bash
MB=$(git merge-base <sua-branch> origin/develop)
echo "$MB"
git diff --name-only "$MB" <sua-branch> | head
```

**Decisão:**
- Se `echo "$MB"` mostrou um hash **e** o `git diff` acima listou arquivos → o `merge-base` está bom, **pule para 0.2**.
- Se `$MB` veio **vazio**, ou o `git diff` veio **vazio** → o rebase furou o `merge-base`. Ache o ponto original pelo reflog abaixo.

**Achar o ponto original pelo reflog:**

```bash
git reflog show <sua-branch> | tail -20
```

Role até o fim (embaixo). A entrada mais antiga costuma dizer `branch: Created from ...` ou `checkout: moving from ... to <sua-branch>`. O **hash à esquerda dessa linha** é o ponto de criação, de semanas atrás, antes de qualquer rebase.

Se não ficar claro, liste os pontos onde a branch já esteve (a última linha, mais antiga, é o nascimento):

```bash
git log -g --oneline <sua-branch>
```

Fixe o `MB` nesse hash e confirme que a lista vem populada:

```bash
MB=<hash-original-que-voce-copiou>
echo "$MB"
git diff --name-only "$MB" <sua-branch> | head
```

Se listou arquivos, achamos o ponto certo. **Esse `$MB` será usado em todos os passos seguintes.**

### 0.2 — Descubra o nome/email exato do colega

```bash
git log "$MB"..origin/develop --format='%an <%ae>' | sort -u
```

Lista todos os autores que commitaram na develop desde o ponto de partida. **Copie a string exata do colega.** Se aparecer um terceiro nome inesperado, entrou trabalho de mais alguém no período — bom saber agora.

### 0.3 — Lista B: arquivos que o COLEGA mexeu

```bash
git log --author="<colega>" --name-only --pretty=format: "$MB"..origin/develop | sort -u | sed '/^$/d' > ../lista-colega.txt
cat ../lista-colega.txt
```

O filtro `--author` pega só os commits dele, então o seu lixo (que também está na develop) fica de fora automaticamente. Esta é a lista do domínio limpo do colega — vamos cruzar com ela nos próximos passos.

### 0.4 — Inventário: o que difere entre a sua branch e a develop

```bash
git diff --name-status <sua-branch> origin/develop | sort -k2 > ../inventario.txt
cat ../inventario.txt
```

Cada linha tem uma letra (lendo no sentido "da sua branch para a develop"):

- **`A`** — existe na develop, **não** existe na sua branch. É **lixo** (classe que o rebase removeu) **ou** arquivo **novo do colega**. Na entrega (Fase 2) todo `A` some — então é preciso garantir que nenhum `A` seja do colega (passo 0.6).
- **`M`** — existe nos dois lados, conteúdo diferente. São os arquivos que você **refez do zero** (sua classe, sua classe de teste). Auditados no passo 0.5.
- **`D`** — existe só na sua branch. É o seu trabalho novo. Fica, sem drama.

### 0.5 — Arquivos que você REFEZ (os `M`) e quais o colega também mexeu

Estes são o coração do seu caso: arquivos que já existiam, você refez do zero, e a versão da develop não vale mais — **mas** você precisa saber se o colega mexeu neles antes de descartar o lado dele.

```bash
# arquivos que existem nos dois lados e diferem (os "M" do inventario)
git diff --name-status <sua-branch> origin/develop | awk '$1=="M"{print $2}' | sort > ../modificados-ambos.txt
cat ../modificados-ambos.txt

# subconjunto perigoso: dos que você refez, quais o COLEGA também mexeu
comm -12 ../modificados-ambos.txt ../lista-colega.txt > ../refiz-e-colega-mexeu.txt
cat ../refiz-e-colega-mexeu.txt
```

Isso te divide em dois grupos:

- **Em `modificados-ambos.txt` mas NÃO em `refiz-e-colega-mexeu.txt`:** você refez, o colega **não** tocou. A versão da develop é puro lixo antigo. **Fica com a sua, sem dó** — não há nada do colega a perder.
- **Em `refiz-e-colega-mexeu.txt`:** você refez **e** o colega mexeu. **Caso perigoso** — sua versão vence no que é seu, mas o colega pode ter adicionado algo que precisa sobreviver. Confira cada um no passo 0.6 antes de deixar a sua vencer.

### 0.6 — Inspecione o que o colega fez nos arquivos perigosos

Para **cada** arquivo em `refiz-e-colega-mexeu.txt` (e para qualquer `A` do inventário que também apareça na `lista-colega.txt`), veja só as linhas do colega:

```bash
git log --author="<colega>" -p "$MB"..origin/develop -- <caminho/do/arquivo>
```

Para ver o estado final da versão dele e copiar trechos:

```bash
git show origin/develop:<caminho/do/arquivo>
```

**Anote**, por arquivo, o que precisa sobreviver da versão do colega. Decisão por arquivo:
- Se o que ele fez já está contemplado na sua reimplementação (ou não vale mais) → fica só a sua versão.
- Se ele adicionou algo que ainda vale (um método, um teste novo que cobre outra coisa) → você traz **essa parte** pra dentro da sua versão na mão (nos passos 5/5.1).

> Ao fim da Fase 0 você tem: a lista do colega, o inventário classificado (A/M/D), a lista de arquivos que você refez, e — o mais importante — a lista curta de arquivos perigosos com anotação do que preservar de cada um.

---

## Fase 1 — Montar a árvore correta na sua branch

### 2 — Backup da sua branch

```bash
git branch backup/antes-de-mexer <sua-branch>
git branch --list "backup/*"
```

Cópia de segurança. Se algo der errado, você volta pra cá sem perder nada. O segundo comando deve listar `backup/antes-de-mexer`.

### 3 — Entre na sua branch

```bash
git switch <sua-branch>
git status
```

A primeira linha do `status` deve dizer `On branch <sua-branch>`. Se disser outra coisa, não avance.

### 4 — Traga o trabalho do colega pra dentro da sua branch

```bash
git merge origin/develop
```

Mescla a develop atual **para dentro** da sua branch, puxando automaticamente o trabalho do colega. Depois disso a branch terá: sua implementação final + trabalho do colega + o lixo (limpo no passo 6).

- Se aparecerem **conflitos** → vá para o passo 5.
- Se disser `Already up to date` ou mesclar sem conflito → vá para o passo 5.1 (ainda precisa conferir os arquivos perigosos).

### 5 — Resolva os conflitos usando as listas como gabarito

O git marca as regiões conflitantes assim:

```
<<<<<<< HEAD
    (a sua versão)
=======
    (a versão da develop / do colega)
>>>>>>> origin/develop
```

Decida em cada bloco (ou junte as duas partes) e **apague os marcadores** (`<<<<<<<`, `=======`, `>>>>>>>`). Use as listas da Fase 0:

- Arquivo que você **refez e o colega NÃO mexeu** (em `modificados-ambos.txt`, fora de `refiz-e-colega-mexeu.txt`) → **sua versão manda**.
- Arquivo **só do colega** (na `lista-colega.txt`, você não mexeu) → **a versão dele manda**, não descarte.
- Arquivo **perigoso** (em `refiz-e-colega-mexeu.txt`) → **junte na mão**: sua versão + as linhas do colega que você anotou no 0.6.

Depois de arrumar cada arquivo:

```bash
git add <caminho/do/arquivo/resolvido>
```

Quando resolver todos, confira que não sobrou nada em "Unmerged paths" e finalize:

```bash
git status
git commit
```

### 5.1 — Confira que o merge não descartou o trabalho do colega (risco silencioso)

O merge pode resolver um arquivo perigoso **sem marcar conflito**, mantendo só a sua versão e descartando a do colega em silêncio. Por isso, para **cada** arquivo da `refiz-e-colega-mexeu.txt`:

```bash
git diff "$MB" HEAD -- <caminho/do/arquivo>
```

Verifique se as linhas do colega (que você anotou no 0.6) estão presentes. Se **não** estiverem, traga na mão editando o arquivo, depois:

```bash
git add <caminho/do/arquivo>
git commit -m "Integra alteracoes do colega em <arquivo>"
```

### 6 — Apague o lixo na mão

Remova as classes fora de lugar e as que deviam ter sido deletadas (os `A` do inventário que **não** são do colega):

```bash
git rm <caminho/da/classe-lixo1> <caminho/da/classe-lixo2>
# pasta inteira: git rm -r <caminho/da/pasta>
git status
```

Os arquivos-lixo devem aparecer como `deleted:`. Confira a lista com calma — só o lixo pode estar saindo. Aí registre:

```bash
git commit -m "Remove classes obsoletas"
```

### 7 — Rode os testes localmente e espere VERDE

```bash
<COMANDO-DE-TESTE-DO-CI>
```

Este é o juiz. **Enquanto vermelho, você não terminou** — volte aos passos 5, 5.1 ou 6. Só avance quando passar.

Neste ponto sua branch está perfeita: implementação final + trabalho do colega + sem lixo + testes passando.

---

## Fase 2 — Entregar via branch nova + PR

A sua branch não pode ir direto pra develop porque, por causa do rebase, o merge de volta manteria o lixo. Os passos abaixo "carimbam" a árvore limpa numa branch que nasce da develop, onde o lixo vira deleção que o git aplica de verdade.

### 8 — Crie uma branch nova nascida da develop atual

```bash
git switch -c deploy/final origin/develop
git status
```

Como `deploy/final` nasce da develop, qualquer arquivo removido aqui vira deleção registrada no merge. O `status` deve dizer `On branch deploy/final`.

### 9 — Substitua o conteúdo dela pela árvore limpa da sua branch

```bash
git rm -rf .
git checkout <sua-branch> -- .
git add -A
git status
```

O primeiro comando esvazia a árvore; o segundo repõe **exatamente** o conteúdo da sua branch limpa. O lixo (que só existia na develop) não volta e vira deleção.

**Prova visual:** no `status`, o lixo aparece como `deleted:` e os arquivos do colega aparecem presentes. Leia a lista inteira — **todo `deleted:` tem que ser lixo**. Se um arquivo legítimo (seu ou do colega) aparecer como deletado, algo saiu errado nos passos 5/6; pare. Se estiver tudo certo:

```bash
git commit -m "Estabiliza develop: versao final limpa, sem classes obsoletas"
```

### 10 — Confirme que a árvore bate com a sua branch

```bash
git diff <sua-branch> deploy/final
```

**O ideal é saída vazia** — significa que `deploy/final` é idêntica à sua branch testada e verde. Se mostrar diferença, sobrou algo; resolva antes de subir.

### 11 — Empurre a branch nova (nunca a develop)

```bash
git push -u origin deploy/final
```

Envia só a branch candidata. A develop protegida não é tocada.

### 12 — Abra o PR e deixe o processo agir

No GitHub, abra um Pull Request de **`deploy/final` → `develop`** e então:

- Abra a aba **"Files changed"** e revise: cada arquivo-lixo deve aparecer como *deleted*, e o trabalho do colega deve estar presente.
- Deixe o **CI rodar**. Como já ficou verde localmente no passo 7, deve passar.
- Se o repo exigir **review** ou **branch atualizada**, atenda. Se pedir atualização porque a develop mudou, é porque alguém empurrou algo — por isso o combinado de o colega segurar os pushes.
- Com CI verde e review ok, **mergeie pelo botão do GitHub**. É o GitHub que faz o merge na branch protegida, não a sua máquina.

---

## Checklist rápido

- [ ] 0.1 Ponto de criação original achado (merge-base OU reflog) — `$MB` correto
- [ ] 0.2 Nome/email do colega copiado
- [ ] 0.3 `lista-colega.txt` gerada
- [ ] 0.4 `inventario.txt` gerado (A / M / D classificados)
- [ ] 0.5 `modificados-ambos.txt` e `refiz-e-colega-mexeu.txt` gerados
- [ ] 0.6 Cada arquivo perigoso inspecionado, anotado o que preservar do colega
- [ ] 2 Backup criado
- [ ] 4 Merge da develop na branch
- [ ] 5 Conflitos resolvidos pelo gabarito das listas
- [ ] 5.1 Cada arquivo perigoso conferido (trabalho do colega presente)
- [ ] 6 Lixo removido (os `A` que não são do colega)
- [ ] 7 Testes locais VERDES
- [ ] 9 `deploy/final` com o lixo aparecendo como `deleted:`
- [ ] 10 `git diff <sua-branch> deploy/final` vazio
- [ ] 12 PR aberto, CI verde, merge pelo GitHub
