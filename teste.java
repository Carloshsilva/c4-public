# Plano para estabilizar a develop

> **Convenção:** onde aparecer `<sua-branch>`, troque pelo nome da sua branch com a implementação final correta (ex.: `feature/minha-alteracao`).
> Onde aparecer `<colega>`, troque pelo nome ou email exato do colega no git (você descobre no passo 0.2).
> Onde aparecer `<COMANDO-DE-TESTE-DO-CI>`, troque pelo mesmo comando que o CI do GitHub roda (está no `.github/workflows/*.yml`, no job que trava o deploy).

**Regras de ouro (o que te queimou antes):**
- Nunca dê `force-push` em nada compartilhado.
- Nunca faça merge local direto na develop — ela é protegida, entra só por PR.
- Combine com o colega de **não empurrar nada pra develop** até seu PR entrar. Se ele empurrar, o alvo se move e você refaz a partir do passo 4.
- O juiz final é a suíte de testes rodando localmente (passo 7). Enquanto vermelho, você não terminou.

---

## Fase 0 — Reconhecimento (montar as listas antes de mesclar)

O objetivo desta fase é substituir a sua memória por três listas objetivas: o que **você** mexeu, o que o **colega** mexeu, e a **interseção** (arquivos perigosos que precisam das duas versões juntas).

### 0.1 — Sincronize e defina o ponto de referência

```bash
git fetch origin
MB=$(git merge-base <sua-branch> origin/develop)
echo $MB
```

`git fetch` atualiza o retrato do servidor sem mexer nas suas branches. `git merge-base` acha o ancestral comum (o ponto de onde você e o colega partiram); a variável `MB` guarda esse commit. **Confira que imprimiu um hash** — se vier vazio, pare.

### 0.2 — Descubra o nome/email exato do colega

```bash
git log $MB..origin/develop --format='%an <%ae>' | sort -u
```

Lista todos os autores que commitaram na develop desde o ponto de partida. **Copie a string exata do colega.** Se aparecer um terceiro nome inesperado, entrou trabalho de mais alguém no período — bom saber agora.

### 0.3 — Lista A: arquivos que VOCÊ mexeu

```bash
git diff --name-only $MB <sua-branch> | sort > ../lista-minhas.txt
cat ../lista-minhas.txt
```

Todos os arquivos que diferem entre o ponto de partida e a sua branch — o seu domínio.
*(Windows sem git-bash: troque `../lista-minhas.txt` por um caminho tipo `C:\temp\lista-minhas.txt`.)*

### 0.4 — Lista B: arquivos que o COLEGA mexeu

```bash
git log --author="<colega>" --name-only --pretty=format: $MB..origin/develop | sort -u | sed '/^$/d' > ../lista-colega.txt
cat ../lista-colega.txt
```

O filtro `--author` pega só os commits dele, então o seu lixo (que também está na develop) fica de fora automaticamente. Resultado: o domínio limpo do colega.

### 0.5 — Lista C: a INTERSEÇÃO (arquivos perigosos)

```bash
comm -12 ../lista-minhas.txt ../lista-colega.txt > ../lista-perigo.txt
cat ../lista-perigo.txt
```

`comm -12` mostra só o que aparece nas duas listas — arquivos que vocês dois tocaram. **Todo arquivo aqui é um caso "classe X"**: precisa da sua versão nova E das mudanças dele juntas. Nunca resolva um destes com `ours` cego.

- **Lista vazia:** vocês trabalharam em domínios separados, sem caso classe X. O merge será quase indolor.
- **Lista com arquivos:** são exatamente esses que exigem atenção manual nos passos 5 e 5.1.

### 0.6 — Inspecione cada arquivo da interseção

Para cada arquivo em `lista-perigo.txt`, veja o que o colega mudou nele:

```bash
git log --author="<colega>" -p $MB..origin/develop -- <caminho/da/classe-X>
```

O `-p` mostra o diff (as linhas dele). **Anote o que precisa estar presente na versão final** além do seu trabalho. Para ver o estado final da versão dele e copiar trechos:

```bash
git show origin/develop:<caminho/da/classe-X>
```

---

## Fase 1 — Montar a árvore correta na sua branch

### 1 — Sincronize (se ainda não fez na Fase 0)

```bash
git fetch origin
```

Atualiza o estado do servidor sem mexer no seu trabalho.

### 2 — Backup da sua branch

```bash
git branch backup/antes-de-mexer <sua-branch>
git branch --list "backup/*"
```

Cria uma cópia de segurança. Se algo der errado, você volta pra cá sem perder nada. O segundo comando deve listar `backup/antes-de-mexer`.

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

Mescla a develop atual **para dentro** da sua branch, puxando automaticamente o trabalho do colega. Depois disso a branch terá três coisas misturadas: sua implementação final + trabalho do colega + o lixo (limpo no passo 6).

- Se aparecerem **conflitos** → vá para o passo 5.
- Se disser `Already up to date` ou mesclar sem conflito → vá para o passo 5.1 (ainda precisa conferir os arquivos de perigo).

### 5 — Resolva os conflitos usando as listas como gabarito

O git marca as regiões conflitantes assim:

```
<<<<<<< HEAD
    (a sua versão)
=======
    (a versão da develop / do colega)
>>>>>>> origin/develop
```

Decida em cada bloco qual versão fica (ou junte as duas), e **apague os marcadores** (`<<<<<<<`, `=======`, `>>>>>>>`). Use as listas para decidir:

- Arquivo **só na Lista A** (seu, não está na de perigo) → **sua versão manda**.
- Arquivo **só na Lista B** (só do colega) → **a versão dele manda**, não descarte.
- Arquivo na **Lista C / perigo** (interseção) → **junte os dois na mão**: sua versão nova + as linhas do colega que você anotou no passo 0.6. Este é o caso classe X.

Depois de arrumar cada arquivo:

```bash
git add <caminho/do/arquivo/resolvido>
```

Quando resolver todos, confira que não sobrou nada em "Unmerged paths" e finalize:

```bash
git status
git commit
```

### 5.1 — Confira que o merge não comeu o trabalho do colega (risco silencioso)

O merge pode resolver uma classe X **sem marcar conflito**, mantendo só a sua versão e descartando a do colega em silêncio. Por isso, para **cada** arquivo da `lista-perigo.txt`:

```bash
git diff $MB HEAD -- <caminho/da/classe-X>
```

Verifique se as linhas do colega (que você viu no passo 0.6) estão presentes. Se **não** estiverem, o merge descartou o trabalho dele — edite o arquivo na mão trazendo as mudanças, depois:

```bash
git add <caminho/da/classe-X>
git commit -m "Integra alteracoes do colega na classe X"
```

### 6 — Apague o lixo na mão

Remova as classes fora de lugar e as que deviam ter sido deletadas:

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

**Prova visual:** no `status`, o lixo aparece como `deleted:` e os arquivos do colega aparecem presentes. Leia a lista inteira — **todo `deleted:` tem que ser lixo**. Se um arquivo legítimo aparecer como deletado, algo saiu errado nos passos 5/6; pare. Se estiver tudo certo:

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

- [ ] 0.1–0.6 Listas montadas (minhas, colega, interseção) e arquivos de perigo inspecionados
- [ ] 2 Backup criado
- [ ] 4 Merge da develop na branch
- [ ] 5 Conflitos resolvidos pelo gabarito das listas
- [ ] 5.1 Cada arquivo de perigo conferido (trabalho do colega presente)
- [ ] 6 Lixo removido
- [ ] 7 Testes locais VERDES
- [ ] 9 `deploy/final` com o lixo aparecendo como `deleted:`
- [ ] 10 `git diff <sua-branch> deploy/final` vazio
- [ ] 12 PR aberto, CI verde, merge pelo GitHub
