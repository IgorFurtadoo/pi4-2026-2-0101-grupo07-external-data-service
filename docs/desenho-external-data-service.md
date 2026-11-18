# Desenho do External Data Service (Container 4)

Escrito em 18/11/2026. Fontes: `especificacao-entrega2.md` (fichas CT18, CT19 e CT20 são deste módulo; fluxos E6 e E7), `desenho-core-api.md` (seções 3.2, 7.2 e 8.6, que fixam como o Core API o chama) e `desenho-frontend.md` (seção 7, histórico). Em divergência valem `docs/contratos/` e este desenho; a Especificação entregue não é emendada.

Legenda:
- **[Contrato]**: já definido na Especificação ou no Core API. Só muda emendando a ficha.
- **[Sugestão]**: proposta deste documento, ainda não decidida.
- **[Decidido]**: decisão do responsável do módulo, com a data.
- **[Aberto]**: decisão ainda não tomada.
- **[Verificado]**: conferido por teste ou por fonte, com a data.

---

## 1. Papel e limites

O External Data Service é o único módulo que fala com APIs de terceiros que não são a Ethereum. Ele:

1. Devolve o preço atual e a série de 7 dias de um ativo, em USD, juntando duas chamadas à CoinGecko. [Contrato: CT18, CT19, CT20, E6]
2. Devolve o histórico de transações de um endereço Ethereum (Sepolia), lido de um indexador público. [Decidido, 18/11/2026: seção 7; fora da Especificação]

Ele **não**: aplica rate limiting (é do Core API, P5), guarda dados (sem banco), conhece usuários nem token, nem fala com o Blockchain Service ou com o RPC. Quem o chama é só o Core API.

## 2. Tecnologias

Mesma base dos outros módulos Java, para a equipe ter um só jeito de trabalhar.

| Item | Escolha | Origem |
|---|---|---|
| Linguagem e build | Java 21, Maven | [Sugestão] igual ao Core API |
| HTTP | Javalin 7.2.4 | [Contrato] Javalin; versão igual à do Core API |
| JSON | Jackson, com números lidos como `BigDecimal` | [Sugestão] |
| Cliente HTTP (CoinGecko e indexador) | `java.net.http.HttpClient` do JDK | [Sugestão] |
| Log e testes | SLF4J + Logback, JUnit 5 | [Sugestão] |
| Banco, cache externo, injeção de dependência | Nenhum | [Sugestão] |

Versões exatas: o agente fixa no `pom.xml` na criação, na última estável compatível com o Java 21, conferida no Maven Central e não de memória.

## 3. Infraestrutura

### 3.1 Container

| Serviço no compose | Imagem | Observação |
|---|---|---|
| `external-data-service` | build do repositório | Dockerfile multi-stage, usuário não root, tags de imagem fixas. Porta **8082** (a que o Core API já espera em `EXTERNAL_DATA_SERVICE_URL`), publicada em `127.0.0.1:8082` para teste por `curl` |

Rede: entra na rede Docker externa `lemma-dev` (a mesma do Core API e do Blockchain Service), para o Core API alcançá-lo em `http://external-data-service:8082`. Não tem banco, então não tem rede própria além dessa.

### 3.2 Variáveis de ambiente

| Variável | Para quê | Exemplo |
|---|---|---|
| `SERVER_PORT` | Porta do Javalin | `8082` |
| `SERVICE_KEY` | Chave compartilhada: o Core API a envia em `X-Service-Key` (o mesmo valor do Core API) | (segredo) |
| `COINGECKO_API_KEY` | Chave do plano Demo, enviada em `x-cg-demo-api-key` (P10) | (segredo) |
| `COINGECKO_BASE_URL` | Base da CoinGecko; existe para os testes apontarem para um servidor falso | `https://api.coingecko.com/api/v3` |
| `COINGECKO_TIMEOUT_SECONDS` | Tempo de resposta de **cada** chamada à CoinGecko | `6` |
| `BLOCKSCOUT_BASE_URL` | Base do indexador (entra no passo E3) | `https://eth-sepolia.blockscout.com/api/v2` |
| `BLOCKSCOUT_TIMEOUT_SECONDS` | Tempo de resposta do indexador (entra no E3) | `6` |
| `HISTORY_CACHE_SECONDS` | Validade do cache do histórico por endereço (entra no E3) | `12` |

Regras da `AppConfig` (como nos outros módulos): lê tudo em uma classe, falha na subida se faltar variável ou se um segredo estiver vazio ou com o valor de exemplo do `.env.example`, e o `toString` não imprime segredos. `.env` fora do git, `.env.example` versionado com valores de exemplo.

Por que 6 s: o CT18 faz **duas** chamadas em sequência (CT19, depois CT20), e o Core API espera até 15 s (`EXTERNAL_DATA_TIMEOUT_SECONDS`). 2 × 6 s mais a conexão cabe nos 15 s, senão o Core API devolveria 502 com este módulo ainda trabalhando. Uma tentativa por chamada, sem retentativa: cada chamada gasta um crédito do plano Demo (10.000 por mês).

## 4. Estrutura de código [Sugestão]

Base `com.lemma.external`. Mesmas regras de dependência dos outros módulos (`controller → service → client`; erro por `ApiException` e um handler único).

```
external/
  Main.java                          monta tudo e sobe o Javalin
  config/AppConfig.java
  controller/MarketDataController.java      GET /market-data/{assetId}      (CT18)
  service/MarketDataService.java            compõe CT19 e CT20
  client/CoinGeckoClient.java               CT19 e CT20 (classe concreta)
  dto/                                      records de saída
  security/ServiceKeyFilter.java            exige X-Service-Key
  exception/ApiException.java
```

Sem interface para o cliente: ele é testado contra um servidor HTTP local do JDK (como o `MarketDataClient` do Core API), e o service só tem uma implementação.

## 5. Rotas

| Contrato | Rota | Autenticação | Sucesso |
|---|---|---|---|
| CT18 `fetchMarketData` | `GET /market-data/{assetId}` | `X-Service-Key` | 200 `{assetId, currency, currentPrice, series}` |
| (histórico, seção 7) | `GET /addresses/{ethereumAddress}/transactions` | `X-Service-Key` | entra no passo E3 |

`X-Service-Key` ausente ou errada: 401 `SERVICE_KEY_INVALID`, comparada em tempo constante (`MessageDigest.isEqual`), **antes** de qualquer outra conferência. Esta é a divergência CT18 × P2 já registrada: a P2 diz que a chamada não tem autenticação, a ficha exige a chave, o desenho segue a ficha.

Corpo de erro: o padrão dos outros módulos (`code` e `message`); o agente confere em `docs/` e segue o que a documentação já definir.

## 6. Mercado (CT18, CT19, CT20)

Ordem [Contrato: E6]: chave → `assetId` → **CT19** (preço) → **CT20** (série) → compor → 200.

- **`assetId`:** `^[a-z0-9-]{1,64}$`, sem normalizar caixa (os ids da CoinGecko são minúsculos). Fora disso, 404 `ASSET_NOT_FOUND` **sem** chamar a CoinGecko. É a mesma regra do Core API, repetida aqui porque o valor vai no caminho da URL externa.
- **CT19** `GET {COINGECKO_BASE_URL}/simple/price?ids={assetId}&vs_currencies=usd`, com `x-cg-demo-api-key`. Resposta esperada `{"ethereum":{"usd":3200.45}}`. Objeto vazio `{}` ou sem o `id`: 404 `ASSET_NOT_FOUND`, **e o CT20 não é chamado** (poupa um crédito). O `id` presente sem `usd` (`{"ethereum":{}}`) é corpo fora do formato: 502. [Verificado, 18/11/2026: a CoinGecko real responde HTTP 200 `{}` para `id` desconhecido.]
- **CT20** `GET {COINGECKO_BASE_URL}/coins/{assetId}/market_chart?vs_currency=usd&days=7`, com a mesma chave. Resposta `{"prices":[[ms, preço], ...]}`. Cada par vira `{timestamp, price}`, com o `timestamp` em ISO 8601 UTC (`Instant.ofEpochMilli(ms).toString()`). A série segue a ordem recebida. HTTP 404 da CoinGecko: 404 `ASSET_NOT_FOUND`. [Verificado, 18/11/2026: `id` desconhecido responde HTTP 404 `{"error":"coin not found"}`; `ethereum` com `days=7` traz 169 pontos, 168 de hora em hora e o último no instante da consulta.]
- **Valores exatos.** Os preços são lidos como `BigDecimal` (Jackson com `USE_BIG_DECIMAL_FOR_FLOATS`, sem passar por `double`) e escritos como número JSON em notação plana (`toPlainString()`), sem notação científica. O Core API conta com isso (ele recusa `1E+999999999` e confere o formato).
- **Resposta que o Core API aceita** (desenho do Core API, seção C7): `assetId` igual ao pedido, `currency` exatamente `USD`, `currentPrice` e cada `price` não negativos, com até 30 casas e 20 dígitos inteiros, `series` **não vazia**, `timestamp` aceito por `Instant.parse`. Qualquer ponto inválido invalida a resposta toda: este módulo **não** devolve resposta parcial; resposta da CoinGecko fora do formato é 502.
- **Erros da CoinGecko** (CT19 e CT20): 401 (chave), 429 (limite), 5xx, falha de conexão, tempo esgotado e corpo ilegível viram 502 `MARKET_DATA_UNAVAILABLE`. Uma tentativa só. [Verificado, 18/11/2026: com um valor falso em `x-cg-demo-api-key`, a CoinGecko real respondeu HTTP 200 com dados reais nas duas chamadas, não 401; chave errada, na prática, não gera 502. O 502 real foi provado por falha de conexão.]
- **Tempos:** conexão em até 2 s e prazo **total** de `COINGECKO_TIMEOUT_SECONDS` por chamada, cobrindo DNS, conexão, TLS, cabeçalhos e corpo (o `request.timeout` do JDK sozinho não corta um corpo lento). A `AppConfig` só aceita de 1 a 6 s. Pior caso do CT18 (CT19 no limite e CT20 esgotando o prazo): 2 × 6 = 12 s, abaixo dos 15 s do Core API, com a conexão já dentro de cada prazo.
- **Log:** só o contrato (CT19 ou CT20) e o status ou o `code`. Nunca a chave, a URL com a consulta ou o corpo. `ASSET_NOT_FOUND` não gera log; `SERVICE_KEY_INVALID` e chave da CoinGecko recusada (401) saem como `ERROR`; os demais, `WARN`.
- **Sem cache** [Sugestão]: o limite do Core API (10 requisições por 60 s, 20 chamadas) e o cache de 60 s do Frontend já protegem a cota. Cache aqui só se a demonstração sofrer.

## 7. Histórico de transações de um endereço [Decidido, 18/11/2026; detalhe no passo E3]

Decisão do histórico em `desenho-frontend.md`, seção 7 (opção D): o Blockscout público da Sepolia, **sem chave**, consultado por este módulo e repassado pelo Core API ao Frontend. Rascunho, a fechar no E3 depois de conferir o Blockscout com `curl`:

- **Contratos novos** [Sugestão de numeração]: CT24 `fetchAddressTransactions` (Core API chama este módulo) e CT25 `blockscoutGetAddressTransactions` (este módulo chama o Blockscout). O Core API ganha o seu próprio (CT26) no passo C10.
- **Rota:** `GET /addresses/{ethereumAddress}/transactions`, `X-Service-Key`. Endereço fora de `^0x[0-9a-fA-F]{40}$`: 400 `ADDRESS_INVALID`, sem chamar o indexador.
- **Chamada ao indexador:** `GET {BLOCKSCOUT_BASE_URL}/addresses/{endereço}/transactions`, sem chave. Só a primeira página, no máximo 20 itens, mais recentes primeiro.
- **Resposta (rascunho):** `{ethereumAddress, network: "SEPOLIA", transactions: [{hash, direction (SENT, RECEIVED ou SELF), counterpartyAddress, amountEth, timestamp, status (CONFIRMED ou FAILED)}]}`. O valor chega em wei e vira ETH por `BigDecimal`, em texto plano e exato, sem `double`. A direção sai da comparação do endereço pedido com `from` e `to`, sem diferenciar maiúsculas. Itens ainda sem bloco (pendentes) ficam de fora.
- **Cache:** em memória, por endereço, com validade de `HISTORY_CACHE_SECONDS` (padrão 12 s) e no máximo 100 endereços, para não gastar o limite do indexador. [Verificado, 18/11/2026: a instância pública responde `x-ratelimit-limit: 180` e `x-ratelimit-remaining` nos cabeçalhos, sem chave. O tamanho da janela (`x-ratelimit-reset`) não está claro; o E3 confirma. Com cache de 12 s, cada endereço custa no máximo 5 consultas por minuto.]
- **Erros:** 400 `ADDRESS_INVALID`, 401 `SERVICE_KEY_INVALID`, 502 `HISTORY_UNAVAILABLE` (indexador fora do ar, limite excedido, corpo fora do formato, tempo esgotado), 500 `INTERNAL_ERROR`.
- **[Verificado, 18/11/2026, `curl` na carteira de demonstração]:** a resposta é `{"items":[...]}`, mais recentes primeiro. Cada item tem `hash`, `timestamp` (ISO 8601 UTC), `block_number`, `status` (`"ok"` na transação bem-sucedida), `result` (`"success"`), `value` (wei, texto), `from.hash` e `to.hash` (objetos; endereços em caixa mista, então comparar sem diferenciar maiúsculas), `fee.value`, `gas_used`. O depósito de 0,0005 ETH que a carteira recebeu aparece como transação comum (`value` `500000000000000`).
- **A conferir no E3 antes de escrever a ficha:** os valores de `status` e `result` para transação com falha e para pendente; `to` nulo (criação de contrato); o formato de paginação (campo `next_page_params`, que o corte do `curl` não mostrou; só a primeira página será usada); e o caso de um **faucet que paga por contrato** (aparece só como "transação interna" e pode não vir nesta rota). Se não aparecer, registrar no relatório como limitação.

## 8. Segurança

- **Chave da CoinGecko:** só em variável de ambiente, nunca no repositório, em log, em resposta ou em exceção (P10). O `.env.example` traz um valor de exemplo que a `AppConfig` recusa.
- **Chave de serviço:** comparação em tempo constante; nunca em log.
- **Entrada na URL externa:** `assetId` e endereço são validados por expressão regular antes de entrar no caminho da chamada externa (sem `../` nem caracteres especiais). Nenhum dado do cliente vai para cabeçalho ou para a consulta sem validação.
- **Rede:** o módulo só é alcançado pelo Core API dentro do Docker, e a porta é publicada só em `127.0.0.1`. Sem HTTPS dentro do compose (testnet).
- **Limitações a registrar no relatório:** a chamada não é idempotente nem tem retentativa; o cache do histórico é em memória (reiniciar zera); os dados do indexador e da CoinGecko são de terceiros e não são conferidos além do formato.

## 9. Mapa de erros

| Situação | HTTP | `code` |
|---|---|---|
| `X-Service-Key` ausente ou errada | 401 | SERVICE_KEY_INVALID |
| `assetId` fora do formato, ou desconhecido na CoinGecko | 404 | ASSET_NOT_FOUND |
| Endereço fora do formato (histórico) | 400 | ADDRESS_INVALID |
| CoinGecko fora do ar, com limite excedido, chave inválida ou resposta fora do formato | 502 | MARKET_DATA_UNAVAILABLE |
| Indexador fora do ar, com limite excedido ou resposta fora do formato (histórico) | 502 | HISTORY_UNAVAILABLE |
| Qualquer outra falha | 500 | INTERNAL_ERROR |

Rotas inexistentes: 404 e 405 com o corpo padrão do Javalin, como nos outros módulos.

## 10. Testes

1. **Unitários, sem rede:** `AppConfig` (falta de variável, segredo vazio ou de exemplo, `toString` sem segredo); `ServiceKeyFilter` (ausente, errada e certa); conversão do instante em milissegundos e dos preços (`BigDecimal` exato, sem notação científica); validação de `assetId`.
2. **`CoinGeckoClient` contra um servidor HTTP local do JDK** (o "CoinGecko falso"): método, caminho, consulta, cabeçalho `x-cg-demo-api-key` presente (o falso guarda só **se** veio certo, nunca o valor), tradução dos erros (401, 429, 5xx, tempo esgotado, corpo ilegível) para 502, `{}` do CT19 para 404 **sem** chamar o CT20, preço e série exatos.
3. **Service e controller:** a resposta do CT18 montada; série vazia vira 502; ponto inválido invalida tudo; a chave de serviço é conferida antes de tudo.
4. **Prova contra a CoinGecko real e o Core API real** (roteiro manual, passo E2): `GET /market/ethereum` pelo Core API, com token, devolve preço e série reais; `assetId` inexistente devolve 404; chave de CoinGecko errada devolve 502. Gasta poucos créditos do plano Demo.

## 11. Ordem de implementação e critério de pronto

Um passo por card do OpenProject, um commit por mudança coerente, rodapé `OP#<id>`.

| # | Passo | Card | Pronto quando |
|---|---|---|---|
| E1 | Esqueleto: Maven, Dockerfile, compose na rede `lemma-dev`, `.env.example`, `AppConfig`, `ApiException` e handler, `ServiceKeyFilter`, `docs/desenho-external-data-service.md` no repositório, README com como rodar | H01.5 (#1315, parte External Data Service) e H01.2 (#865, parte External Data Service) | `docker compose up` sobe o serviço na 8082; variável faltando ou de exemplo derruba a subida com mensagem clara; rota inexistente devolve 404; testes verdes |
| E2 | Mercado: CT18, CT19 e CT20, contra o CoinGecko falso e depois contra o real, ligado ao Core API real | H06.1 (#879) | Os testes da seção 10 passam; `GET /market/ethereum` pelo Core API devolve preço e série reais; os três erros reais provados por `curl` |
| E3 | Histórico: conferir o Blockscout por `curl`, escrever as fichas CT24 e CT25, rota, cache e testes | novo (a criar) | Histórico de um endereço real da Sepolia devolvido por `curl`; indexador falso nos testes; erros e cache provados |

Dependências: E1 antes de todos; E2 e E3 só dependem do E1. Depois do E3 vêm o F6 no Frontend e o C10 no Core API (`desenho-frontend.md`, seção 13.2).

## 12. Decisões e divergências

### Decisões

1. **Mesma pilha do Core API** (Java 21, Javalin 7.2.4, Jackson, `HttpClient` do JDK), sem banco e sem injeção de dependência. [Sugestão]
2. **Porta 8082 e rede `lemma-dev`**, para o Core API alcançá-lo sem mudar nada nele. [Sugestão]
3. **`X-Service-Key` obrigatória** (segue a ficha CT18, não a P2). [Contrato]
4. **Uma tentativa por chamada externa e 6 s por chamada**, para caber nos 15 s do Core API e não gastar créditos. [Sugestão]
5. **Preços exatos** (`BigDecimal`, notação plana), **sem resposta parcial**. [Sugestão]
6. **CT19 antes do CT20**, e `ASSET_NOT_FOUND` do CT19 poupa o CT20. [Sugestão]
7. **Sem cache no mercado**; cache curto só no histórico. [Sugestão]
8. **Histórico no External Data Service, Blockscout sem chave** (`desenho-frontend.md`, seção 7). [Decidido, 18/11/2026]

### Divergências da Especificação (vão ao relatório final)

1. **CT18 × P2:** a P2 diz que a chamada ao External Data Service não tem autenticação; a ficha do CT18 exige `X-Service-Key`. Vale a ficha.
2. **CT18 × E6:** o E6 chama `GET /market/{assetId}` neste módulo; a ficha do CT18 diz `GET /market-data/{assetId}`. Vale a ficha.
3. **Histórico:** a Especificação descreve este módulo como integração de "APIs externas de mercado" e não prevê histórico. Passa a existir um histórico por endereço (CT24 e CT25), lido do Blockscout.