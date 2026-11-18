package com.lemma.external.service;

import com.lemma.external.client.BlockscoutClient;
import com.lemma.external.dto.AddressTransaction;
import com.lemma.external.dto.AddressTransactionsResponse;
import com.lemma.external.exception.ApiException;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * CT24 (seção 7 do desenho): endereço → cache → CT25 (Blockscout) → resposta.
 *
 * Endereço fora de ^0x[0-9a-fA-F]{40}$ é 400 ADDRESS_INVALID sem chamar o
 * Blockscout e sem log. O cache usa o endereço em minúsculas como chave, então a
 * mesma carteira em outra caixa usa a mesma entrada; só respostas de sucesso
 * entram (a falha já chega do cliente como 502 HISTORY_UNAVAILABLE, depois de
 * logada, e não é guardada). O ethereumAddress da resposta é o pedido, como foi
 * enviado.
 */
public class AddressHistoryService {

    static final String NETWORK = "SEPOLIA";

    private final BlockscoutClient blockscout;
    private final HistoryCache cache;

    public AddressHistoryService(BlockscoutClient blockscout, HistoryCache cache) {
        this.blockscout = blockscout;
        this.cache = cache;
    }

    public AddressTransactionsResponse fetchAddressTransactions(String ethereumAddress) {
        if (ethereumAddress == null || !BlockscoutClient.ADDRESS.matcher(ethereumAddress).matches()) {
            throw ApiException.addressInvalid();
        }
        String key = ethereumAddress.toLowerCase(Locale.ROOT);
        Optional<List<AddressTransaction>> cached = cache.get(key);
        List<AddressTransaction> transactions;
        if (cached.isPresent()) {
            transactions = cached.get();
        } else {
            transactions = blockscout.addressTransactions(ethereumAddress);
            cache.put(key, transactions);
        }
        return new AddressTransactionsResponse(ethereumAddress, NETWORK, transactions);
    }
}
