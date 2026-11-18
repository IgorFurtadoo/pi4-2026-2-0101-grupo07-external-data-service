# CT25: blockscoutGetAddressTransactions

| Linha | Valor |
|---|---|
| Fronteira | HTTP (API externa) |
| Quem chama | External Data Service |
| Quem responde | Blockscout público da Sepolia |
| Atende | CT24 (histórico da carteira; fora da Especificação) |
| Como chamar | `GET https://eth-sepolia.blockscout.com/api/v2/addresses/{endereço}/transactions` |

Sem chave e sem cabeçalho de autenticação. A base vem de `BLOCKSCOUT_BASE_URL`. O endereço vai no caminho em minúsculas, depois de validado pelo CT24; nada vai na consulta. Uma tentativa por chamada, conexão em até 2 s e prazo total de `BLOCKSCOUT_TIMEOUT_SECONDS` (1 a 10 s). Serviço externo: os nomes seguem a API v2 do Blockscout.

## Entrada

| Campo | Tipo | Obrigatório | Exemplo |
|---|---|---|---|
| endereço (caminho) | texto, `^0x[0-9a-fA-F]{40}$` | sim | 0xbdd26d8f9b9876d59cd6ebe1f25a1286b8ecacd5 |

## Saída

Resposta `200`: `{"items": [...], "next_page_params": ...}`, itens mais recentes primeiro, até 50 por página.

| Campo | Tipo | Obrigatório | Exemplo |
|---|---|---|---|
| items | lista de (objeto (transação)) | sim | lista vazia se o endereço não tiver transações |
| next_page_params | objeto ou nulo | não | nulo na última página; ignorado (só a primeira página é usada) |

Campos de cada item que o External Data Service lê (os demais são ignorados):

| Campo | Tipo | Obrigatório num item minerado | Exemplo |
|---|---|---|---|
| block_number | inteiro ou nulo | sim (nulo = pendente, item fora) | 12144876 |
| hash | texto | sim | 0x336be534b01ce174e559209ffdde79b15ffeb5f47a66b16f77737d45fcef7598 |
| from.hash | texto | sim | 0x6bdB5129e34773c822044a2078e6ba1576A258E6 |
| to | objeto ou nulo | sim (nulo = criação de contrato) | `{"hash": "0xbdd26D8f…CaCD5", ...}` |
| created_contract | objeto ou nulo | não | nulo, ou `{"hash": ...}` na criação de contrato |
| value | texto (wei, inteiro) | sim | "500000000000000" |
| timestamp | data e hora | sim | 2026-11-16T04:12:00.000000Z |
| status | um de: ok, error | sim | ok |

O campo `result` (`success`, `Out of gas`, `awaiting_internal_transactions`, `pending`...) não é usado: o estado sai só de `status`.

## Erros

| Quando | O que volta |
|---|---|
| Endereço fora do formato | HTTP 422 `{"errors":[{"title":"Invalid value",...}]}` (confirmado). Não acontece na prática: o CT24 recusa antes com 400 ADDRESS_INVALID. Se vier, o External Data Service responde HTTP 502, `code` HISTORY_UNAVAILABLE |
| Limite da instância excedido | HTTP 429. O External Data Service responde HTTP 502, `code` HISTORY_UNAVAILABLE |
| Blockscout fora do ar ou tempo esgotado | Falha de conexão ou HTTP 5xx. O External Data Service responde HTTP 502, `code` HISTORY_UNAVAILABLE |
| Corpo ilegível ou fora do formato (sem `items` lista; item minerado com campo obrigatório ausente ou inválido, `status` fora de `ok` e `error`, ou que não envolve o endereço) | O External Data Service responde HTTP 502, `code` HISTORY_UNAVAILABLE, sem resposta parcial |

O log traz só o contrato e o status ou o motivo (`CT25 blockscoutGetAddressTransactions: HTTP 429`), em WARN; nunca a URL, o endereço ou o corpo.

## Nota [Verificado em 18/11/2026]

Conferido com o Blockscout real por `curl` (9 requisições ao todo, contando a prova pelo External Data Service):

- **Carteira de demonstração** (`0xbdd26d8f9b9876d59cd6ebe1f25a1286b8ecacd5`): HTTP 200, `Content-Type: application/json; charset=utf-8`, 13 itens e `"next_page_params": null`. Cada item traz, além dos campos da tabela, `result`, `fee`, `gas_*`, `nonce`, `position`, `confirmations`, `transaction_types`, `token_transfers`, `raw_input` e outros. `from` e `to` são objetos com `hash` em caixa mista, `is_contract`, `name` etc.; `created_contract` vem nulo. `value` vem em texto; `timestamp` com microssegundos.
- **Transação com falha:** na própria carteira, `0x14d26d92…`, com bloco, `status "error"` e `result "Out of gas"`. Na lista geral (`GET /api/v2/transactions`), 18 de 50 itens minerados tinham `status "error"` com `result "awaiting_internal_transactions"`, e outros 3 de 50 `status "error"` com `result "Out of gas"`: o `result` varia, o `status` não.
- **Pendente** (`GET /api/v2/transactions?filter=pending`): 50 de 50 itens com `status` nulo, `result "pending"`, `block_number` nulo e `timestamp` nulo.
- **Endereço válido sem transações** (endereço aleatório): HTTP 200, corpo exato `{"items":[],"next_page_params":null}`.
- **Endereço inválido** (`0x123`): HTTP 422, `{"errors":[{"title":"Invalid value","source":{"pointer":"/address_hash_param"},"detail":"Invalid format. Expected ~r/^0x([A-Fa-f0-9]{40})$/"}]}`.
- **Paginação:** `next_page_params` é nulo na última página e um objeto quando há mais (na lista geral, `{"index":42,"block_number":12159167}`).
- **Limite da instância pública:** `x-ratelimit-limit: 180`, `x-ratelimit-remaining` e `x-ratelimit-reset`, sem chave. O `x-ratelimit-reset` é o tempo em **milissegundos** até o fim de uma **janela fixa de 60 s alinhada ao minuto**: às 04:39:51 (cabeçalho `Date`) valia 8367 ms (fim às 04:40:00); às 04:40:20, 39994 ms (fim às 04:41:00); às 04:41:06, 54299 ms (fim às 04:42:00); e o `remaining` voltava a 179 na primeira chamada de cada minuto. Em chamadas seguidas, cai na medida do tempo decorrido (8367, 7863, 7603). Portanto, 180 requisições por minuto, **presumido por IP** (não dá para provar de uma máquina só).
- **Não confirmado na rede:** (1) **criação de contrato** (`to` nulo): nenhuma nas 100 transações recentes olhadas, e o filtro `type=contract_creation` foi recusado com HTTP 422 (`"Invalid value for enum"`); o tratamento (destino = `created_contract.hash`) segue os campos que o corpo mostra e está coberto só pelos testes com o Blockscout falso. (2) **Transação pendente dentro da lista de um endereço**: a carteira de demonstração não tinha nenhuma; o formato pendente foi visto só na lista geral. (3) **Faucet que paga por contrato**: os 13 itens da carteira são transferências diretas; um pagamento feito por contrato é transação interna e pode não aparecer nesta rota; não foi testado.
