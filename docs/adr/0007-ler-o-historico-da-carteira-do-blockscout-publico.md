# ADR 0007: ler o histórico da carteira do Blockscout público

**Status:** aceito

**Contexto:** O Core API só sabia listar uma transação pelo `taskId`. Com o sistema rodando, apareceram três problemas: quem fechava a aba durante um envio não tinha como reencontrar a transação; depois de um tempo esgotado no envio não havia como conferir se ele foi aceito; e a carteira não mostrava nenhuma atividade, nem os ETH recebidos de fora (por exemplo, da torneira). As transações recebidas nunca passam pelo Lemma, então o banco do Core API não as conhece. Um nó JSON-RPC não lista transações por endereço.

**Decisão:** O histórico vem de um indexador público da Sepolia, o Blockscout (`eth-sepolia.blockscout.com/api/v2`), que não exige chave. O External Data Service consulta o Blockscout (CT25), devolve as até 20 transações mais recentes do endereço, já mineradas, no formato do Lemma (`fetchAddressTransactions`), e guarda o resultado por 12 s por endereço. O Core API expõe `GET /wallet/transactions` (CT26), sempre com o endereço do usuário do token, e o Frontend mostra a lista na carteira (E10).

**Alternativas consideradas:**

- Montar o histórico só com a tabela `eth_transaction` do Core API: descartada porque não mostra o que a carteira recebeu de fora do Lemma.
- Etherscan API: descartada porque exige chave de API e conta, e acrescentaria mais um segredo ao sistema.
- Percorrer blocos pelo provedor RPC para achar as transações do endereço: descartada por custo (milhares de chamadas) e por depender dos limites do nó público.
- Adiar o histórico: descartada porque o problema de perder a transação ao fechar a aba afeta o E5 na prática.

**Consequências:**

- Positivas: a carteira mostra enviadas e recebidas sem guardar nada novo no banco; nenhuma chave nova; uma falha do Blockscout vira `502 HISTORY_UNAVAILABLE` só na lista.
- Negativas: depende de um serviço público de terceiros, com limite de 180 requisições por minuto; transações pendentes não aparecem (continuam no acompanhamento por `taskId`); só a primeira página (20 itens); é um requisito novo (E10) que não estava na Entrega 1.
