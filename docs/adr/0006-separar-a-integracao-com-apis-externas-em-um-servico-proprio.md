# ADR 0006: separar a integração com APIs externas em um serviço próprio

**Status:** aceito

**Contexto:** A Proposta Arquitetural e a Entrega 1 previam três containers de aplicação, com a chamada à CoinGecko dentro do Core API. Depois da Entrega 1, a equipe decidiu manter um repositório por implementação e não ter certeza de que a CoinGecko seria a fonte definitiva de dados de mercado. O Core API já concentra usuários, autenticação, orquestração das operações de carteira e estado das transações. Uma API pública de terceiros tem limites, chaves e formatos próprios, que mudam sem aviso.

**Decisão:** Criar o External Data Service, um serviço Java/Javalin sem banco, em contêiner próprio, responsável por toda consulta a APIs públicas de terceiros que não sejam a rede Ethereum: hoje, preço e histórico de mercado na CoinGecko (CT18 a CT20) e, desde 18/11/2026, o histórico de transações por endereço no Blockscout (ADR 0007). Ele é chamado só pelo Core API, com `X-Service-Key`, e devolve dados já no formato do Lemma. O nome é neutro e não cita a CoinGecko. O rate limiting continua no Core API (premissa P5).

**Alternativas consideradas:**

- Manter a integração dentro do Core API, como na Entrega 1: descartada porque mistura no serviço principal a chave da CoinGecko, os formatos de terceiros e os tempos limite de uma API externa, e porque contraria a decisão de um repositório por implementação.
- Chamar a CoinGecko direto do Frontend: descartada porque exporia a chave de API no navegador e tiraria do sistema o controle do limite de requisições (E7).

**Consequências:**

- Positivas: a chave da CoinGecko fica só neste serviço; trocar de fornecedor de dados não altera o Core API, desde que o CT18 seja mantido; uma falha da CoinGecko ou do Blockscout vira `502` no painel e não afeta saldo nem envio; o serviço não tem banco nem estado (só um cache de 12 s do histórico).
- Negativas: mais um contêiner e mais uma chamada de rede (Core API → External Data Service) em cada consulta de mercado; o texto dos entregáveis E6 e E7 e o OE4 ("três containers") ficaram desatualizados e foram ajustados no Documento de Projeto; a premissa P2 dizia que essa chamada não teria autenticação, e o contrato CT18 passou a exigir `X-Service-Key`.
