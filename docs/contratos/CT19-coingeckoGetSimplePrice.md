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
| `ids` desconhecido | A ser confirmado em teste: provavelmente HTTP 200 com objeto vazio `{}`. O External Data Service trata como HTTP 404, `code` ASSET_NOT_FOUND |
| CoinGecko fora do ar ou tempo esgotado | Falha de conexão ou HTTP 5xx. O External Data Service responde HTTP 502, `code` MARKET_DATA_UNAVAILABLE |
