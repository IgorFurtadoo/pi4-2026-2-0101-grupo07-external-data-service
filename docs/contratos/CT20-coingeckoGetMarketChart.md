# CT20: coingeckoGetMarketChart

| Linha | Valor |
|---|---|
| Fronteira | HTTP (API externa) |
| Quem chama | External Data Service |
| Quem responde | CoinGecko API |
| Atende | E6 e E7 |
| Como chamar | `GET https://api.coingecko.com/api/v3/coins/{id}/market_chart` |

Plano Demo, com a chave no cabeçalho `x-cg-demo-api-key` (premissa P10). Com `days` igual a 7, a CoinGecko devolve pontos de hora em hora. Serviço externo: os nomes seguem a documentação da CoinGecko.

## Entrada

| Campo | Tipo | Obrigatório | Exemplo |
|---|---|---|---|
| x-cg-demo-api-key (cabeçalho) | texto | sim | CG-xxxxxxxxxxxxxxxxxxxx |
| id (caminho) | texto | sim | ethereum |
| vs_currency (consulta) | texto | sim | usd |
| days (consulta) | inteiro | sim | 7 |

## Saída

Resposta `200`.

| Campo | Tipo | Obrigatório | Exemplo |
|---|---|---|---|
| prices | lista de (lista de decimal) | sim | [[1760875200000, 3187.9], [1760878800000, 3191.2]] |

Cada par é [instante em milissegundos desde 1970, preço em USD]. O External Data Service converte o instante para data e hora ISO 8601 UTC.

## Erros

| Quando | O que volta |
|---|---|
| Chave ausente ou inválida | HTTP 401. O External Data Service responde HTTP 502, `code` MARKET_DATA_UNAVAILABLE |
| Limite do plano Demo excedido | HTTP 429. O External Data Service responde HTTP 502, `code` MARKET_DATA_UNAVAILABLE |
| `id` inválido | A ser confirmado em teste (provável HTTP 404). O External Data Service trata como HTTP 404, `code` ASSET_NOT_FOUND |
| CoinGecko fora do ar ou tempo esgotado | Falha de conexão ou HTTP 5xx. O External Data Service responde HTTP 502, `code` MARKET_DATA_UNAVAILABLE |
