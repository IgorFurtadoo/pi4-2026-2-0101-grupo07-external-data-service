# CT24: fetchAddressTransactions

| Linha | Valor |
|---|---|
| Fronteira | HTTP |
| Quem chama | Core API |
| Quem responde | External Data Service |
| Atende | Histórico da carteira (`desenho-frontend.md`, seção 7, opção D; fora da Especificação) |
| Como chamar | `GET /addresses/{ethereumAddress}/transactions` |

Devolve as transações mais recentes de um endereço da Sepolia, lidas do Blockscout público (CT25) e convertidas para este formato. Só a primeira página do Blockscout: até 20 transações já mineradas, mais recentes primeiro, sem paginação. Pendentes (ainda sem bloco) ficam de fora. Resultado guardado em cache por endereço durante `HISTORY_CACHE_SECONDS` (12 s no exemplo).

## Entrada

| Campo | Tipo | Obrigatório | Exemplo |
|---|---|---|---|
| X-Service-Key (cabeçalho) | texto | sim | dev-service-key |
| ethereumAddress (caminho) | texto, `^0x[0-9a-fA-F]{40}$` | sim | 0xbdd26D8f9B9876D59CD6ebE1F25a1286b8ECaCD5 |

O endereço é aceito em qualquer caixa nos 40 dígitos (o `0x` é minúsculo); a mesma carteira em caixas diferentes usa a mesma entrada do cache.

## Saída

Resposta `200`.

| Campo | Tipo | Obrigatório | Exemplo |
|---|---|---|---|
| ethereumAddress | texto | sim | o endereço pedido, como foi enviado |
| network | um de: SEPOLIA | sim | SEPOLIA |
| transactions | lista de (objeto (AddressTransaction)) | sim | até 20 itens, mais recentes primeiro; lista vazia se o endereço não tiver transações |

Campos de `objeto (AddressTransaction)`:

| Campo | Tipo | Obrigatório | Exemplo |
|---|---|---|---|
| hash | texto | sim | 0x336be534b01ce174e559209ffdde79b15ffeb5f47a66b16f77737d45fcef7598 |
| direction | um de: SENT, RECEIVED, SELF | sim | RECEIVED |
| counterpartyAddress | texto ou nulo | sim | 0x6bdB5129e34773c822044a2078e6ba1576A258E6 |
| amountEth | texto (decimal exato) | sim | "0.0005" |
| timestamp | data e hora | sim | 2026-11-16T04:12:00Z |
| status | um de: CONFIRMED, FAILED | sim | CONFIRMED |

- **direction:** comparação do endereço pedido com o remetente e o destino, sem diferenciar maiúsculas. Remetente e destino iguais ao pedido: SELF; só o remetente: SENT; só o destino: RECEIVED.
- **counterpartyAddress:** a outra ponta (o destino em SENT, o remetente em RECEIVED, o próprio endereço em SELF), na caixa que o Blockscout manda. Na criação de contrato, o destino é o contrato criado; é **nulo** só quando a criação falhou e não há contrato.
- **amountEth:** valor em ETH, convertido do wei por `BigDecimal` (18 casas), em **texto** e notação plana, sem zeros à direita e sem notação científica (`"0.000000000000000001"`, `"0.0005"`, `"0"`). É texto, não número JSON, porque o `JSON.parse` do navegador perde dígitos.
- **timestamp:** instante do bloco em ISO 8601 UTC, normalizado por `Instant.parse(...).toString()` (o Blockscout manda `2026-11-16T04:12:00.000000Z`; sai `2026-11-16T04:12:00Z`).
- **status:** CONFIRMED quando o Blockscout diz `ok`; FAILED quando diz `error` (transação minerada que reverteu).

Exemplo:

```json
{
  "ethereumAddress": "0xbdd26D8f9B9876D59CD6ebE1F25a1286b8ECaCD5",
  "network": "SEPOLIA",
  "transactions": [
    {
      "hash": "0x336be534b01ce174e559209ffdde79b15ffeb5f47a66b16f77737d45fcef7598",
      "direction": "RECEIVED",
      "counterpartyAddress": "0x6bdB5129e34773c822044a2078e6ba1576A258E6",
      "amountEth": "0.0005",
      "timestamp": "2026-11-16T04:12:00Z",
      "status": "CONFIRMED"
    }
  ]
}
```

## Erros

| Quando | O que volta |
|---|---|
| `X-Service-Key` ausente ou incorreta (conferida antes de tudo) | HTTP 401, `code` SERVICE_KEY_INVALID |
| `ethereumAddress` fora de `^0x[0-9a-fA-F]{40}$` (o Blockscout não é chamado) | HTTP 400, `code` ADDRESS_INVALID |
| Blockscout fora do ar, com limite excedido (429), HTTP 5xx, tempo esgotado, corpo ilegível ou fora do formato | HTTP 502, `code` HISTORY_UNAVAILABLE |
| Falha inesperada | HTTP 500, `code` INTERNAL_ERROR |

Sem resposta parcial: um item minerado com campo obrigatório ausente ou inválido, ou com status desconhecido, torna a resposta toda 502. Erro nunca entra no cache.

## Nota [Verificado em 18/11/2026]

Conferido com o External Data Service no Docker, ligado ao Blockscout real, por `curl`:

- **Carteira de demonstração** (`0xbdd26d8f9b9876d59cd6ebe1f25a1286b8ecacd5`): HTTP 200 com 13 transações (8 SENT, 5 RECEIVED; 12 CONFIRMED, 1 FAILED). A primeira (mais recente) é `0x336be534…7598`, RECEIVED, `0.0005`, CONFIRMED, `2026-11-16T04:12:00Z`; a última é `0xb76bb5cc…e7f7`, RECEIVED, `0.055`, CONFIRMED, `2026-11-09T02:23:48Z`. A FAILED é a `0x14d26d92…`, que o Blockscout marca `status "error"`, `result "Out of gas"`.
- **Cache:** a mesma carteira pedida de novo em caixa mista respondeu em 5 ms, com as mesmas transações e o `ethereumAddress` na caixa enviada.
- **Endereço válido sem transações:** HTTP 200 `{"ethereumAddress":"…","network":"SEPOLIA","transactions":[]}`.
- **`0x123`:** HTTP 400 ADDRESS_INVALID. **Sem chave:** HTTP 401 SERVICE_KEY_INVALID.
- **Blockscout inalcançável** (container temporário com `BLOCKSCOUT_BASE_URL` apontando para uma porta fechada): HTTP 502 HISTORY_UNAVAILABLE, com log `CT25 blockscoutGetAddressTransactions: falha de rede (ConnectException)`.
