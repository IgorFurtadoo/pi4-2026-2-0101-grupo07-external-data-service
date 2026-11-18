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

   Mantenha `SERVER_PORT=8082`. O serviço não sobe se faltar variável, se um segredo estiver vazio ou com o valor de exemplo; o motivo aparece no log.

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
