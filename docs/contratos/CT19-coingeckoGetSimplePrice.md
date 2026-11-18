# CT19: coingeckoGetSimplePrice

| Linha | Valor |
|---|---|
| Fronteira | HTTP (API externa) |
| Quem chama | External Data Service |
| Quem responde | CoinGecko API |
| Atende | E6 e E7 |
| Como chamar | `GET https://api.coingecko.com/api/v3/simple/price` |

Plano Demo, com a chave no cabeçalho `x-cg-demo-api-key`, lida de variável de ambiente (premissa P10). Serviço externo: os nomes seguem a documentação da CoinGecko.

## Entrada

| Campo | Tipo | Obrigatório | Exemplo |
|---|---|---|---|
| x-cg-demo-api-key (cabeçalho) | texto | sim | CG-xxxxxxxxxxxxxxxxxxxx |
| ids (consulta) | texto | sim | ethereum |
| vs_currencies (consulta) | texto | sim | usd |

## Saída

Resposta `200`: objeto cujo nome de campo é o `id` do ativo.

| Campo | Tipo | Obrigatório | Exemplo |
|---|---|---|---|
| {id}.usd | decimal | sim | 3200.45 |

Exemplo: `{"ethereum": {"usd": 3200.45}}`.

## Erros

| Quando | O que volta |
|---|---|
| Chave ausente ou inválida | HTTP 401. O External Data Service responde HTTP 502, `code` MARKET_DATA_UNAVAILABLE |
| Limite do plano Demo excedido | HTTP 429. O External Data Service responde HTTP 502, `code` MARKET_DATA_UNAVAILABLE |
| `ids` desconhecido | HTTP 200 com objeto vazio `{}` (confirmado; ver nota abaixo). O External Data Service trata como HTTP 404, `code` ASSET_NOT_FOUND, e não chama o CT20 |
| CoinGecko fora do ar ou tempo esgotado | Falha de conexão ou HTTP 5xx. O External Data Service responde HTTP 502, `code` MARKET_DATA_UNAVAILABLE |

## Nota [Verificado em 18/11/2026]

Conferido com a CoinGecko real (plano Demo), por `curl` e pelo External Data Service:

- **`ids` desconhecido** (`ids=assetinexistente&vs_currencies=usd`): HTTP 200, `Content-Type: application/json; charset=utf-8`, corpo `{}`.
- **`ids=ethereum`**: HTTP 200 no formato do exemplo (`{"ethereum": {"usd": ...}}`); o External Data Service leu o preço `1807.09`.
- **Chave inválida:** com um valor falso em `x-cg-demo-api-key`, a CoinGecko **não** respondeu 401: respondeu HTTP 200 com o preço real. A linha "Chave ausente ou inválida" fica como o tratamento previsto caso a CoinGecko passe a recusar a chave; na prática, chave errada não gera 502.
- **Fora do formato:** `{"{id}": {}}` (id presente sem `usd`), `usd` em texto, negativo, com mais de 30 casas ou mais de 20 dígitos inteiros: o External Data Service responde HTTP 502, `code` MARKET_DATA_UNAVAILABLE.
