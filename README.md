# Lemma

Aplicação web de carteira de criptoativos custodial para a rede Ethereum Sepolia Testnet, que permi>

## Integrantes

- Igor de Toledo Furtado
- Lucas Athanasio Bueno de Andrade
- Guillermo Perdomo Sevilla Neto
- Nicollas Fabbio

## Documentação de fluxos e contratos

Os fluxos do sistema (visão geral e E1–E9) estão no repositório do frontend: https://github.com/Igo>

Os contratos deste módulo estão em docs/contratos/.

O desenho do módulo (papel, variáveis, rotas, erros e ordem de implementação) está em docs/desenho-external-data-service.md.

## Como rodar

Pré-requisito: Docker com o plugin compose. O serviço não tem banco; ele só entra na rede `lemma-dev`, a mesma do Core API e do Blockchain Service.

1. Crie a rede compartilhada, se ainda não existir:

   ```sh
   docker network inspect lemma-dev >/dev/null 2>&1 || docker network create lemma-dev
   ```

2. Crie o `.env` (fora do git) a partir do exemplo e troque os dois valores `TROCAR_...`:

   ```sh
   cp .env.example .env
   ```

   - `SERVICE_KEY`: o mesmo valor do `SERVICE_KEY` do Core API.
   - `COINGECKO_API_KEY`: a chave do plano Demo da CoinGecko.

   Mantenha `SERVER_PORT=8082`. As variáveis do histórico (`BLOCKSCOUT_BASE_URL`, `BLOCKSCOUT_TIMEOUT_SECONDS` e `HISTORY_CACHE_SECONDS`) não são segredo e podem ficar com os valores do exemplo; quem já tem um `.env` antigo precisa acrescentá-las. O serviço não sobe se faltar variável, se um segredo estiver vazio ou com o valor de exemplo, ou se um valor estiver fora dos limites; o motivo aparece no log.

3. Suba o serviço:

   ```sh
   docker compose up --build -d
   docker compose logs -f external-data-service
   ```

   A porta fica publicada só em `127.0.0.1:8082`. Na rede `lemma-dev`, o Core API o alcança em `http://external-data-service:8082`.

4. Confira: toda rota exige o cabeçalho `X-Service-Key`, e rota inexistente devolve 404.

   ```sh
   curl -i http://127.0.0.1:8082/nao-existe
   ```

5. Para derrubar: `docker compose down`.

### Testes

Com Java 21 e Maven, sem rede externa:

```sh
mvn test
```

## Rotas

Toda rota exige o cabeçalho `X-Service-Key` com o `SERVICE_KEY` do `.env` (o mesmo do Core API), conferido antes de qualquer outra coisa.

### `GET /market-data/{assetId}` (CT18)

Preço atual e série de 7 dias em USD de um ativo da CoinGecko (`assetId` é o id da CoinGecko, por exemplo `ethereum`). Faz duas chamadas em sequência: CT19 (preço) e CT20 (série). Contrato em docs/contratos/CT18-fetchMarketData.md.

```sh
curl -s -H "X-Service-Key: $SERVICE_KEY" http://127.0.0.1:8082/market-data/ethereum
```

| Resposta | Quando |
|---|---|
| 200 `{assetId, currency: "USD", currentPrice, series: [{timestamp, price}]}` | Preços exatos, em notação plana; `timestamp` em ISO 8601 UTC, na ordem da CoinGecko |
| 401 `SERVICE_KEY_INVALID` | `X-Service-Key` ausente ou errada |
| 404 `ASSET_NOT_FOUND` | `assetId` fora de `^[a-z0-9-]{1,64}$` (sem chamar a CoinGecko) ou desconhecido na CoinGecko |
| 502 `MARKET_DATA_UNAVAILABLE` | CoinGecko fora do ar, com limite excedido, chave recusada, tempo esgotado ou resposta fora do formato |

Cada chamada à CoinGecko tem uma tentativa e prazo de `COINGECKO_TIMEOUT_SECONDS` (de 1 a 6 s); cada requisição gasta até 2 créditos do plano Demo.

### `GET /addresses/{ethereumAddress}/transactions` (CT24)

Histórico de um endereço da Sepolia, lido do Blockscout público sem chave (CT25): até 20 transações já mineradas, mais recentes primeiro (só a primeira página; pendentes ficam de fora). Contratos em docs/contratos/CT24-fetchAddressTransactions.md e docs/contratos/CT25-blockscoutGetAddressTransactions.md.

```sh
curl -s -H "X-Service-Key: $SERVICE_KEY" \
  http://127.0.0.1:8082/addresses/0xbdd26d8f9b9876d59cd6ebe1f25a1286b8ecacd5/transactions
```

| Resposta | Quando |
|---|---|
| 200 `{ethereumAddress, network: "SEPOLIA", transactions: [{hash, direction, counterpartyAddress, amountEth, timestamp, status}]}` | `direction` SENT, RECEIVED ou SELF; `amountEth` em **texto** decimal exato (`"0.0005"`); `timestamp` em ISO 8601 UTC; `status` CONFIRMED ou FAILED. Endereço sem transações: `transactions: []` |
| 400 `ADDRESS_INVALID` | Endereço fora de `^0x[0-9a-fA-F]{40}$` (sem chamar o Blockscout) |
| 401 `SERVICE_KEY_INVALID` | `X-Service-Key` ausente ou errada |
| 502 `HISTORY_UNAVAILABLE` | Blockscout fora do ar, com limite excedido, tempo esgotado ou resposta fora do formato (sem resposta parcial) |

Uma tentativa por chamada, com prazo de `BLOCKSCOUT_TIMEOUT_SECONDS` (de 1 a 10 s). O resultado fica em cache por endereço (sem diferenciar maiúsculas) durante `HISTORY_CACHE_SECONDS` (de 1 a 60 s), para até 100 endereços; erro não entra no cache. A instância pública do Blockscout limita 180 requisições por minuto.
