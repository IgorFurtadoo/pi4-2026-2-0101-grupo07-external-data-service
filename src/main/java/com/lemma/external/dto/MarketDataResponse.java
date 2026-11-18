package com.lemma.external.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Resposta 200 do CT18: { assetId, currency, currentPrice, series: [ { timestamp, price } ] }.
 * currency é sempre USD; a série vem na ordem recebida do CT20 e nunca é vazia.
 */
public record MarketDataResponse(String assetId, String currency, BigDecimal currentPrice, List<PricePoint> series) {
}
