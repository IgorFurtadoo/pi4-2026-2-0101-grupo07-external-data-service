package com.lemma.external.dto;

import java.math.BigDecimal;

/**
 * Um ponto da série do CT18: instante em ISO 8601 UTC (Instant.toString do
 * milissegundo recebido no CT20) e preço exato em USD, escrito como número JSON em
 * notação plana (JsonConfig).
 */
public record PricePoint(String timestamp, BigDecimal price) {
}
