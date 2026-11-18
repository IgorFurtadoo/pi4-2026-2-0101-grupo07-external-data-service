package com.lemma.external.client;

import com.lemma.external.LogCapture;
import com.lemma.external.client.FakeCoinGecko.Contract;
import com.lemma.external.client.FakeCoinGecko.Received;
import com.lemma.external.dto.PricePoint;
import com.lemma.external.exception.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.math.BigDecimal;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static com.lemma.external.client.FakeCoinGecko.VALID_PRICES;
import static com.lemma.external.client.FakeCoinGecko.marketChartBody;
import static com.lemma.external.client.FakeCoinGecko.simplePriceBody;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CoinGeckoClient (CT19 e CT20) contra a CoinGecko falsa, sem rede externa. */
class CoinGeckoClientTest {

    /** Chave obviamente falsa, só para os testes. */
    static final String API_KEY = "chave-coingecko-de-teste-falsa";

    private static final String CT19_FORMAT = "WARN CT19 coingeckoGetSimplePrice: HTTP 200 com corpo fora do formato";
    private static final String CT20_FORMAT = "WARN CT20 coingeckoGetMarketChart: HTTP 200 com corpo fora do formato";

    /** 30 casas exatas e 20 dígitos inteiros: os limites aceitos. */
    private static final String THIRTY_PLACES = "0.000000001234567890123456789012";
    private static final String TWENTY_INTEGER_DIGITS = "12345678901234567890.5";

    private FakeCoinGecko fake;
    private CoinGeckoClient client;

    @BeforeEach
    void start() {
        fake = new FakeCoinGecko(API_KEY);
        client = new CoinGeckoClient(fake.baseUrl(), API_KEY, Duration.ofSeconds(2));
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    /** 502 MARKET_DATA_UNAVAILABLE com exatamente esta linha de log. */
    private static void assertUnavailable(String expectedLog, Executable call) {
        try (LogCapture logs = new LogCapture(CoinGeckoClient.class)) {
            ApiException e = assertThrows(ApiException.class, call);
            assertEquals(502, e.getStatus());
            assertEquals("MARKET_DATA_UNAVAILABLE", e.getCode());
            assertEquals(expectedLog, logs.all());
            assertFalse(logs.all().contains(API_KEY));
        }
    }

    // ---------- CT19 ----------

    @Test
    void simplePriceSendsExactRequestWithTheKey() {
        fake.reply(Contract.CT19, 200, simplePriceBody("ethereum", "3200.45"));

        client.simplePrice("ethereum");

        assertEquals(List.of(new Received(Contract.CT19, "GET", "/api/v3/simple/price",
                "ids=ethereum&vs_currencies=usd", true)), fake.requests());
    }

    @Test
    void simplePriceIsExact() {
        String[][] cases = {
                {"3200.45", "3200.45"},
                {"3200.450", "3200.450"},
                {"3201", "3201"},
                {"0", "0"},
                {"1.2e-7", "0.00000012"},
                {"1.2E-7", "0.00000012"},
                {"1E+2", "100"},
                {THIRTY_PLACES, THIRTY_PLACES},
                {TWENTY_INTEGER_DIGITS, TWENTY_INTEGER_DIGITS}};
        for (String[] c : cases) {
            fake.reply(Contract.CT19, 200, simplePriceBody("ethereum", c[0]));

            BigDecimal price = client.simplePrice("ethereum").orElseThrow();

            assertEquals(new BigDecimal(c[0]), price, c[0]);           // mesmo valor e mesma escala
            assertEquals(c[1], price.toPlainString(), c[0]);
        }
    }

    @Test
    void unknownIdIsEmptyWithoutLog() {
        for (String body : new String[] {"{}", simplePriceBody("bitcoin", "90000")}) {
            fake.reply(Contract.CT19, 200, body);
            try (LogCapture logs = new LogCapture(CoinGeckoClient.class)) {
                assertEquals(Optional.empty(), client.simplePrice("ethereum"), body);
                assertEquals("", logs.all(), body);
            }
        }
    }

    @Test
    void simplePriceOutOfFormatIs502() {
        String[] bodies = {
                "isto não é json",
                "",
                "[]",
                "null",
                "\"ethereum\"",
                "{\"ethereum\":{\"usd\":3200.45}",              // cortado
                "{\"ethereum\":{}}",                           // sem usd
                "{\"ethereum\":null}",
                "{\"ethereum\":3200.45}",
                "{\"ethereum\":{\"eur\":3000}}",
                simplePriceBody("ethereum", "\"3200.45\""),    // texto
                simplePriceBody("ethereum", "null"),
                simplePriceBody("ethereum", "true"),
                simplePriceBody("ethereum", "[3200.45]"),
                simplePriceBody("ethereum", "-0.01"),
                simplePriceBody("ethereum", THIRTY_PLACES + "3"),          // 31 casas
                simplePriceBody("ethereum", "1" + TWENTY_INTEGER_DIGITS),  // 21 dígitos inteiros
                simplePriceBody("ethereum", "1E+999999999"),
                simplePriceBody("ethereum", "1E-999999999")};
        for (String body : bodies) {
            fake.reply(Contract.CT19, 200, body);
            assertUnavailable(CT19_FORMAT, () -> client.simplePrice("ethereum"));
        }
    }

    @Test
    void simplePriceErrorStatusIs502() {
        assertStatusIs502(Contract.CT19, "CT19 coingeckoGetSimplePrice", () -> client.simplePrice("ethereum"),
                new int[] {400, 401, 403, 404, 429, 500, 502, 503});
    }

    // ---------- CT20 ----------

    @Test
    void marketChartSendsExactRequestWithTheKey() {
        fake.reply(Contract.CT20, 200, marketChartBody(VALID_PRICES));

        client.marketChart("ethereum");

        assertEquals(List.of(new Received(Contract.CT20, "GET", "/api/v3/coins/ethereum/market_chart",
                "vs_currency=usd&days=7", true)), fake.requests());
    }

    @Test
    void marketChartIsExactAndKeepsTheOrder() {
        // Fora de ordem de propósito: a série segue a ordem recebida.
        fake.reply(Contract.CT20, 200, marketChartBody("[[1760882400000,3201],"
                + "[1760875200000,3187.90],"
                + "[1760878800123,1.2e-7],"
                + "[1760886000000," + THIRTY_PLACES + "],"
                + "[0,1E+2]]"));

        List<PricePoint> series = client.marketChart("ethereum").orElseThrow();

        assertEquals(List.of(
                new PricePoint("2025-10-19T14:00:00Z", new BigDecimal("3201")),
                new PricePoint("2025-10-19T12:00:00Z", new BigDecimal("3187.90")),
                new PricePoint("2025-10-19T13:00:00.123Z", new BigDecimal("1.2E-7")),
                new PricePoint("2025-10-19T15:00:00Z", new BigDecimal(THIRTY_PLACES)),
                new PricePoint("1970-01-01T00:00:00Z", new BigDecimal("1E+2"))), series);
        assertEquals("3187.90", series.get(1).price().toPlainString());
        assertEquals("0.00000012", series.get(2).price().toPlainString());
        assertEquals("100", series.get(4).price().toPlainString());
    }

    @Test
    void marketChart404IsEmptyWithoutLog() {
        fake.reply(Contract.CT20, 404, "{\"error\":\"coin not found\"}");
        try (LogCapture logs = new LogCapture(CoinGeckoClient.class)) {
            assertEquals(Optional.empty(), client.marketChart("ethereum"));
            assertEquals("", logs.all());
        }
    }

    @Test
    void marketChartOutOfFormatIs502() {
        String good = "[1760875200000,3187.9]";
        String[] bodies = {
                "isto não é json",
                "",
                "[]",
                "{}",
                "{\"prices\":null}",
                "{\"prices\":{}}",
                "{\"prices\":\"[]\"}",
                marketChartBody("[]"),                                   // série vazia
                marketChartBody("[[1760875200000]]"),                    // par com 1 item
                marketChartBody("[[1760875200000,3187.9,1]]"),           // com 3
                marketChartBody("[{\"t\":1760875200000,\"p\":3187.9}]"),
                marketChartBody("[[1760875200000.5,3187.9]]"),           // ms não inteiro
                marketChartBody("[[1.7608752E12,3187.9]]"),
                marketChartBody("[[\"1760875200000\",3187.9]]"),         // ms texto
                marketChartBody("[[99999999999999999999,3187.9]]"),      // ms fora do long
                marketChartBody("[[1760875200000,\"3187.9\"]]"),         // preço texto
                marketChartBody("[[1760875200000,null]]"),
                marketChartBody("[[1760875200000,-3187.9]]"),            // negativo
                marketChartBody("[[1760875200000," + THIRTY_PLACES + "3]]"),
                marketChartBody("[[1760875200000,1" + TWENTY_INTEGER_DIGITS + "]]"),
                marketChartBody("[[1760875200000,1E+999999999]]"),
                // Um ponto ruim no meio de bons invalida tudo.
                marketChartBody("[" + good + "," + good + ",[1760882400000,-1]," + good + "]"),
                marketChartBody("[" + good + ",null," + good + "]")};
        for (String body : bodies) {
            fake.reply(Contract.CT20, 200, body);
            assertUnavailable(CT20_FORMAT, () -> client.marketChart("ethereum"));
        }
    }

    @Test
    void marketChartErrorStatusIs502() {
        assertStatusIs502(Contract.CT20, "CT20 coingeckoGetMarketChart", () -> client.marketChart("ethereum"),
                new int[] {400, 401, 403, 429, 500, 502, 503});
    }

    // ---------- comuns ----------

    /** 401 sai como ERROR (chave recusada); os demais, WARN com o status. Corpo nunca no log. */
    private void assertStatusIs502(Contract contract, String name, Executable call, int[] statuses) {
        for (int status : statuses) {
            fake.reply(contract, status, "{\"error\":\"corpo-que-nao-vai-ao-log\"}");
            String expected = status == 401
                    ? "ERROR " + name + ": HTTP 401; a CoinGecko recusou a chave"
                    : "WARN " + name + ": HTTP " + status;
            assertUnavailable(expected, call);
        }
    }

    @Test
    void timeoutIs502WithinTheDeadline() {
        CoinGeckoClient fast = new CoinGeckoClient(fake.baseUrl(), API_KEY, Duration.ofMillis(300));
        fake.reply(Contract.CT19, 200, simplePriceBody("ethereum", "1")).delay(Contract.CT19, 3_000);
        fake.reply(Contract.CT20, 200, marketChartBody(VALID_PRICES)).delay(Contract.CT20, 3_000);

        assertTimeoutPreemptively(Duration.ofMillis(1_500), () -> assertUnavailable(
                "WARN CT19 coingeckoGetSimplePrice: tempo esgotado", () -> fast.simplePrice("ethereum")));
        assertTimeoutPreemptively(Duration.ofMillis(1_500), () -> assertUnavailable(
                "WARN CT20 coingeckoGetMarketChart: tempo esgotado", () -> fast.marketChart("ethereum")));
    }

    /** O prazo é total: cabeçalhos na hora e corpo devagar também esgota o tempo. */
    @Test
    void slowBodyIs502WithinTheDeadline() {
        CoinGeckoClient fast = new CoinGeckoClient(fake.baseUrl(), API_KEY, Duration.ofMillis(300));
        fake.reply(Contract.CT20, 200, marketChartBody(VALID_PRICES)).slowBody(Contract.CT20, 3_000);

        assertTimeoutPreemptively(Duration.ofMillis(1_500), () -> assertUnavailable(
                "WARN CT20 coingeckoGetMarketChart: tempo esgotado", () -> fast.marketChart("ethereum")));
    }

    @Test
    void connectionRefusedIs502() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        CoinGeckoClient offline = new CoinGeckoClient("http://127.0.0.1:" + closedPort + "/api/v3", API_KEY,
                Duration.ofSeconds(2));

        assertUnavailable("WARN CT19 coingeckoGetSimplePrice: falha de rede (ConnectException)",
                () -> offline.simplePrice("ethereum"));
        assertUnavailable("WARN CT20 coingeckoGetMarketChart: falha de rede (ConnectException)",
                () -> offline.marketChart("ethereum"));
    }

    /** O log de todas as falhas nunca traz a chave, a URL, a consulta, o assetId nem o corpo. */
    @Test
    void logNeverContainsKeyUrlQueryOrBody() {
        try (LogCapture logs = new LogCapture(CoinGeckoClient.class)) {
            fake.reply(Contract.CT19, 401, "{\"status\":{\"error_message\":\"corpo-secreto\"}}");
            assertThrows(ApiException.class, () -> client.simplePrice("bitcoin"));
            fake.reply(Contract.CT20, 200, "{\"prices\":\"corpo-secreto\"}");
            assertThrows(ApiException.class, () -> client.marketChart("bitcoin"));
            fake.reply(Contract.CT20, 503, "corpo-secreto");
            assertThrows(ApiException.class, () -> client.marketChart("bitcoin"));

            String all = logs.all();
            assertEquals(3, logs.lines().size(), all);
            for (String forbidden : new String[] {API_KEY, "bitcoin", "ids=", "vs_currency", "127.0.0.1",
                    "/api/v3", "corpo-secreto"}) {
                assertFalse(all.contains(forbidden), forbidden);
            }
        }
    }

    @Test
    void assetIdOutsideTheFormatNeverReachesTheUrl() {
        for (String assetId : new String[] {null, "", "ETHEREUM", "eth/../x", "eth?x=1", "a%2Fb", "x".repeat(65)}) {
            assertThrows(IllegalArgumentException.class, () -> client.simplePrice(assetId), assetId);
            assertThrows(IllegalArgumentException.class, () -> client.marketChart(assetId), assetId);
        }
        assertTrue(fake.requests().isEmpty());
    }
}
