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
| `id` inválido | HTTP 404 com corpo `{"error":"coin not found"}` (confirmado; ver nota abaixo). O External Data Service trata como HTTP 404, `code` ASSET_NOT_FOUND |
| CoinGecko fora do ar ou tempo esgotado | Falha de conexão ou HTTP 5xx. O External Data Service responde HTTP 502, `code` MARKET_DATA_UNAVAILABLE |

## Nota [Verificado em 18/11/2026]

Conferido com a CoinGecko real (plano Demo), por `curl` e pelo External Data Service:

- **`id` desconhecido** (`/coins/assetinexistente/market_chart?vs_currency=usd&days=7`): HTTP 404, `Content-Type: application/json; charset=utf-8`, corpo `{"error":"coin not found"}`.
- **`id=ethereum`, `days=7`:** HTTP 200 com 169 pares em `prices`: 168 de hora em hora (hora cheia, por exemplo `2026-11-11T05:00:00Z`) e, no fim, um ponto no instante da consulta (por exemplo `2026-11-18T04:20:30Z`). Instante em milissegundos inteiros; preços como número JSON com até 13 casas (por exemplo `2062.135215537902`).
- **Chave inválida:** com um valor falso em `x-cg-demo-api-key`, a CoinGecko **não** respondeu 401: respondeu HTTP 200 com a série real. Na prática, chave errada não gera 502.
- **Fora do formato:** `prices` ausente ou vazia, par que não seja `[ms inteiro, preço]`, ou preço negativo, em texto, com mais de 30 casas ou mais de 20 dígitos inteiros: o External Data Service responde HTTP 502, `code` MARKET_DATA_UNAVAILABLE, sem resposta parcial.
