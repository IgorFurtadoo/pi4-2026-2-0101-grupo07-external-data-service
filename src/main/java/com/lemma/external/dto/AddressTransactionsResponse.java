package com.lemma.external.dto;

import java.util.List;

/**
 * Resposta 200 do CT24: { ethereumAddress, network, transactions }.
 * ethereumAddress é o pedido, como foi enviado; network é sempre SEPOLIA;
 * transactions tem até 20 transações mineradas, mais recentes primeiro (pode ser vazia).
 */
public record AddressTransactionsResponse(String ethereumAddress, String network,
                                          List<AddressTransaction> transactions) {
}
