package com.lemma.external.config;

import com.lemma.external.dto.MarketDataResponse;
import com.lemma.external.dto.PricePoint;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Números exatos: leitura em BigDecimal sem double e escrita em notação plana. */
class JsonConfigTest {

    @Test
    void writesBigDecimalInPlainNotation() {
        MarketDataResponse response = new MarketDataResponse("ethereum", "USD", new BigDecimal("1.2E-7"), List.of(
                new PricePoint("2025-10-19T12:00:00Z", new BigDecimal("1E+2")),
                new PricePoint("2025-10-19T13:00:00.123Z", new BigDecimal("3200.450"))));

        assertEquals("{\"assetId\":\"ethereum\",\"currency\":\"USD\",\"currentPrice\":0.00000012,\"series\":["
                        + "{\"timestamp\":\"2025-10-19T12:00:00Z\",\"price\":100},"
                        + "{\"timestamp\":\"2025-10-19T13:00:00.123Z\",\"price\":3200.450}]}",
                JsonConfig.jsonMapper().toJsonString(response, MarketDataResponse.class));
    }

    @Test
    void readsDecimalsExactlyKeepingTrailingZeros() throws Exception {
        var root = JsonConfig.reader().readTree(
                "{\"a\":3200.450,\"b\":1.2e-7,\"c\":0.1,\"d\":0.000000001234567890123456789012}");

        assertEquals(new BigDecimal("3200.450"), root.get("a").decimalValue());
        assertEquals(new BigDecimal("1.2E-7"), root.get("b").decimalValue());
        assertEquals(new BigDecimal("0.1"), root.get("c").decimalValue());        // nunca 0.1000000000000000055...
        assertEquals(new BigDecimal("0.000000001234567890123456789012"), root.get("d").decimalValue());
    }
}
