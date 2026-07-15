# Pacote Postman — Selic Pre-Matching API v2.0

Contém a collection com o contrato da API e os environments separados por ambiente.

## Arquivos
- `Selic-PreMatching-v2.postman_collection.json` — a collection (todos os endpoints v2.0).
- `Selic-Sandbox.postman_environment.json` — ambiente Sandbox (internet pública).
- `Selic-Producao.postman_environment.json` — ambiente Produção (RTM).
- `Selic-Homologacao.postman_environment.json` — ambiente Homologação (RTM) *(bônus)*.

## Como o time usa (é só isso)
1. No Postman: **Import** → arraste os 4 arquivos.
2. Selecione o Environment desejado no canto superior direito.
3. Preencha **`client_id`** e **`client_secret`** no Environment. **Nada mais precisa mudar.**
4. Dispare qualquer request. O token OAuth2 é obtido e renovado **automaticamente** e
   injetado como `Authorization: Bearer {{access_token}}` em toda a collection.

O request **Autenticação → Obter token** existe para uso manual/debug, mas no dia a dia
não é necessário — o pre-request script da collection cuida disso.

## Autenticação
OAuth2 `client_credentials` (Keycloak, realm `logon`). Endpoint em `{{token_url}}`.

## Variáveis (no Environment)
| Variável | Papel |
|---|---|
| `base_url` | Base da API (já inclui `/pre-matching`) |
| `token_url` | Endpoint do token OAuth2 |
| `client_id` / `client_secret` | Credenciais — **a única coisa que o time troca** |
| `access_token` / `access_token_expiry` | Gerenciados automaticamente |
| `dataMovimento`, `idNegocio`, `idEspecificacao`, `idRequisicao`, `idAssociacao` | Helpers reutilizados nos requests |

## Base URLs por ambiente
- **Sandbox:** `https://api.sandbox.selic.gov.br/pre-matching`
- **Produção (RTM):** `https://api-pre-matching.rtm.selic.gov.br/pre-matching`
- **Homologação (RTM):** `https://api-pre-matching-hml.rtm.selic.gov.br/pre-matching`

## Dois pontos a confirmar antes de produção
1. **`token_url` de Produção/Homologação:** a documentação pública só traz o endpoint de token
   do **Sandbox**. Preenchi Produção/Homologação seguindo o mesmo padrão de realm no host da RTM,
   mas **confirme o host/caminho do token com o Selic/RTM** — se for diferente, ajuste só a
   variável `token_url` no Environment.
2. **Corpo do `POST /negocios`:** o schema exato do `NegocioDTO` não estava no trecho público do
   OpenAPI que consegui ler. O body é um **template** com os campos documentados; confira contra o
   Swagger oficial. Todos os demais bodies (associações, especificações, confirmações, quebra de
   lote etc.) vêm dos exemplos oficiais do contrato.

## Rate limit
100 req/min. A resposta traz cabeçalhos `RateLimit-Limit`, `RateLimit-Remaining`, `RateLimit-Reset`.
Ao exceder: HTTP 429.

---
Fonte: contrato OpenAPI/Swagger e Manual Técnico de Conectividade — developers.selic.gov.br
