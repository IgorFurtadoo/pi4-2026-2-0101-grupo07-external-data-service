# CT18: fetchMarketData

| Linha | Valor |
|---|---|
| Fronteira | HTTP |
| Quem chama | Core API |
| Quem responde | External Data Service |
| Atende | E6 e E7 |
| Como chamar | `GET /market-data/{assetId}` |

Reúne preço atual (CT19) e série de 7 dias (CT20) da CoinGecko e devolve tudo em USD. O Core API é quem aplica o rate limiting (premissa P5).

## Entrada

| Campo | Tipo | Obrigatório | Exemplo |
|---|---|---|---|
| X-Service-Key (cabeçalho) | texto | sim | dev-service-key |
| assetId (caminho) | texto | sim | ethereum |

## Saída

Resposta `200`.

| Campo | Tipo | Obrigatório | Exemplo |
|---|---|---|---|
| assetId | texto | sim | ethereum |
| currency | um de: USD | sim | USD |
| currentPrice | decimal | sim | 3200.45 |
| series | lista de (objeto (PricePoint)) | sim | lista de pontos de preço dos últimos 7 dias |

Campos de `objeto (PricePoint)`:

| Campo | Tipo | Obrigatório | Exemplo |
|---|---|---|---|
| timestamp | data e hora | sim | 2026-10-19T12:00:00Z |
| price | decimal | sim | 3187.9 |

## Erros

| Quando | O que volta |
|---|---|
| `X-Service-Key` ausente ou incorreta | HTTP 401, `code` SERVICE_KEY_INVALID |
| `assetId` desconhecido | HTTP 404, `code` ASSET_NOT_FOUND |
| CoinGecko API indisponível, com limite excedido ou chave inválida | HTTP 502, `code` MARKET_DATA_UNAVAILABLE |
| Falha inesperada | HTTP 500, `code` INTERNAL_ERROR |
