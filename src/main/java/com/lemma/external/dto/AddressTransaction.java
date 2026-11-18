package com.lemma.external.dto;

/**
 * Uma transação do histórico de um endereço (CT24, seção 7 do desenho), já
 * convertida do formato do Blockscout (CT25).
 *
 * - direction: comparação do endereço pedido com o remetente e o destino, sem
 *   diferenciar maiúsculas.
 * - counterpartyAddress: a outra ponta, na caixa que o Blockscout mandou. Na
 *   criação de contrato, o contrato criado; null só quando a criação falhou e não
 *   há contrato.
 * - amountEth: valor exato em ETH como texto em notação plana ("0.0005"), nunca
 *   número JSON: o navegador perderia dígitos.
 * - timestamp: instante do bloco em ISO 8601 UTC (Instant.toString).
 */
public record AddressTransaction(
        String hash,
        Direction direction,
        String counterpartyAddress,
        String amountEth,
        String timestamp,
        Status status) {

    public enum Direction { SENT, RECEIVED, SELF }

    /** CONFIRMED: status "ok" no Blockscout; FAILED: status "error". */
    public enum Status { CONFIRMED, FAILED }
}
