package com.lemma.external;

import com.lemma.external.client.CoinGeckoClient;
import com.lemma.external.client.FakeCoinGecko;
import com.lemma.external.client.FakeCoinGecko.Contract;
import com.lemma.external.client.FakeCoinGecko.Received;
import com.lemma.external.controller.ErrorHandler;
import com.lemma.external.security.ServiceKeyFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static com.lemma.external.TestApp.SERVICE_KEY;
import static com.lemma.external.client.FakeCoinGecko.VALID_PRICES;
import static com.lemma.external.client.FakeCoinGecko.marketChartBody;
import static com.lemma.external.client.FakeCoinGecko.simplePriceBody;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CT18 GET /market-data/{assetId} por HTTP real, com a CoinGecko falsa. */
class MarketDataRouteTest {

    private static final String ASSET_NOT_FOUND = "{\"code\":\"ASSET_NOT_FOUND\",\"message\":\"Ativo não encontrado.\"}";
    private static final String UNAVAILABLE =
            "{\"code\":\"MARKET_DATA_UNAVAILABLE\",\"message\":\"Dados de mercado indisponíveis.\"}";
    private static final String SERVICE_KEY_INVALID =
            "{\"code\":\"SERVICE_KEY_INVALID\",\"message\":\"Chave de serviço ausente ou incorreta.\"}";

    private static final Received CT19_ETHEREUM = new Received(Contract.CT19, "GET", "/api/v3/simple/price",
            "ids=ethereum&vs_currencies=usd", true);
    private static final Received CT20_ETHEREUM = new Received(Contract.CT20, "GET",
            "/api/v3/coins/ethereum/market_chart", "vs_currency=usd&days=7", true);

    private TestApp app = new TestApp().start();

    @AfterEach
    void stop() {
        app.stop();
    }

    private FakeCoinGecko fake() {
        return app.coinGecko;
    }

    private HttpResponse<String> getMarketData(String assetId) throws Exception {
        return app.get("/market-data/" + assetId, SERVICE_KEY);
    }

    private static void assertBody(int status, String body, HttpResponse<String> response) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(body, response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
    }

    @Test
    void returnsPriceAndSeriesExactlyInPlainNotation() throws Exception {
        fake().reply(Contract.CT19, 200, simplePriceBody("ethereum", "1.2e-7"));
        fake().reply(Contract.CT20, 200, marketChartBody(VALID_PRICES));

        HttpResponse<String> response = getMarketData("ethereum");

        assertBody(200, "{\"assetId\":\"ethereum\",\"currency\":\"USD\",\"currentPrice\":0.00000012,\"series\":["
                + "{\"timestamp\":\"2025-10-19T12:00:00Z\",\"price\":3187.9},"
                + "{\"timestamp\":\"2025-10-19T13:00:00Z\",\"price\":3190.12},"
                + "{\"timestamp\":\"2025-10-19T14:00:00Z\",\"price\":3201}]}", response);
        assertEquals(List.of(CT19_ETHEREUM, CT20_ETHEREUM), fake().requests());   // CT19, depois CT20
    }

    @Test
    void manyDecimalsAndScientificNotationStayExact() throws Exception {
        fake().reply(Contract.CT19, 200, simplePriceBody("shiba-inu", "0.000000001234567890123456789012"));
        fake().reply(Contract.CT20, 200, marketChartBody("[[1760878800123,8.5E-6],[1760882400000,1E+2]]"));

        HttpResponse<String> response = getMarketData("shiba-inu");

        assertBody(200, "{\"assetId\":\"shiba-inu\",\"currency\":\"USD\","
                + "\"currentPrice\":0.000000001234567890123456789012,\"series\":["
                + "{\"timestamp\":\"2025-10-19T13:00:00.123Z\",\"price\":0.0000085},"
                + "{\"timestamp\":\"2025-10-19T14:00:00Z\",\"price\":100}]}", response);
    }

    /** {} do CT19: 404 sem log, e o CT20 não é chamado (poupa um crédito). */
    @Test
    void unknownInSimplePriceIs404WithoutCallingMarketChart() throws Exception {
        for (String body : new String[] {"{}", simplePriceBody("bitcoin", "90000")}) {
            app.stop();
            app = new TestApp().start();
            fake().reply(Contract.CT19, 200, body);
            fake().reply(Contract.CT20, 200, marketChartBody(VALID_PRICES));
            try (LogCapture client = new LogCapture(CoinGeckoClient.class);
                 LogCapture errors = new LogCapture(ErrorHandler.class)) {

                assertBody(404, ASSET_NOT_FOUND, getMarketData("ethereum"));
                assertEquals(List.of(CT19_ETHEREUM), fake().requests(), body);
                assertEquals("", client.all() + errors.all());
            }
        }
    }

    @Test
    void marketChart404Is404WithoutLog() throws Exception {
        fake().reply(Contract.CT19, 200, simplePriceBody("ethereum", "3200.45"));
        fake().reply(Contract.CT20, 404, "{\"error\":\"coin not found\"}");
        try (LogCapture client = new LogCapture(CoinGeckoClient.class)) {

            assertBody(404, ASSET_NOT_FOUND, getMarketData("ethereum"));
            assertEquals(List.of(CT19_ETHEREUM, CT20_ETHEREUM), fake().requests());
            assertEquals("", client.all());
        }
    }

    /** Falha do CT19: 502 e o CT20 não é chamado. */
    @Test
    void simplePriceFailureIs502WithoutCallingMarketChart() throws Exception {
        fake().reply(Contract.CT20, 200, marketChartBody(VALID_PRICES));
        String[][] cases = {
                {"401", "{\"status\":{\"error_code\":10002}}"},
                {"429", "{\"status\":{\"error_code\":429}}"},
                {"500", "erro"},
                {"503", ""},
                {"200", "isto não é json"},
                {"200", "{\"ethereum\":{}}"},
                {"200", simplePriceBody("ethereum", "-1")}};
        for (String[] c : cases) {
            fake().reply(Contract.CT19, Integer.parseInt(c[0]), c[1]);
            int before = fake().requests().size();

            assertBody(502, UNAVAILABLE, getMarketData("ethereum"));
            assertEquals(List.of(CT19_ETHEREUM), fake().requests().subList(before, fake().requests().size()),
                    c[0] + " " + c[1]);
        }
    }

    @Test
    void marketChartFailureIs502() throws Exception {
        fake().reply(Contract.CT19, 200, simplePriceBody("ethereum", "3200.45"));
        String good = "[1760875200000,3187.9]";
        String[][] cases = {
                {"401", "{}"},
                {"429", "{}"},
                {"500", "{}"},
                {"502", "{}"},
                {"200", "isto não é json"},
                {"200", "{\"market_caps\":[]}"},
                {"200", marketChartBody("[]")},                                          // série vazia
                {"200", marketChartBody("[" + good + ",[1760878800000]," + good + "]")}, // ponto inválido
                {"200", marketChartBody("[" + good + ",[1760878800000,-0.5]]")}};        // preço negativo
        for (String[] c : cases) {
            fake().reply(Contract.CT20, Integer.parseInt(c[0]), c[1]);

            assertBody(502, UNAVAILABLE, getMarketData("ethereum"));
        }
    }

    @Test
    void timeoutIs502() throws Exception {
        app.stop();
        app = new TestApp(Duration.ofMillis(300)).start();
        fake().reply(Contract.CT19, 200, simplePriceBody("ethereum", "1")).delay(Contract.CT19, 3_000);

        assertTimeoutPreemptively(Duration.ofMillis(1_500),
                () -> assertBody(502, UNAVAILABLE, getMarketData("ethereum")));
        assertEquals(List.of(CT19_ETHEREUM), fake().requests());
    }

    /**
     * Pior caso: CT19 responde perto do prazo e o CT20 esgota o prazo. O CT18 inteiro
     * termina em cerca de 2 × prazo (na produção, 2 × 6 s = 12 s, abaixo dos 15 s do Core API).
     */
    @Test
    void worstCaseIsBoundedByTwoDeadlines() throws Exception {
        app.stop();
        app = new TestApp(Duration.ofMillis(500)).start();
        fake().reply(Contract.CT19, 200, simplePriceBody("ethereum", "1")).delay(Contract.CT19, 250);
        fake().reply(Contract.CT20, 200, marketChartBody(VALID_PRICES)).delay(Contract.CT20, 5_000);

        assertTimeoutPreemptively(Duration.ofMillis(1_500),
                () -> assertBody(502, UNAVAILABLE, getMarketData("ethereum")));
        assertEquals(List.of(CT19_ETHEREUM, CT20_ETHEREUM), fake().requests());
    }

    /** Fora de ^[a-z0-9-]{1,64}$: 404 sem chamar a CoinGecko e sem log. */
    @Test
    void assetIdOutsideTheFormatIs404WithoutCallingCoinGecko() throws Exception {
        try (LogCapture client = new LogCapture(CoinGeckoClient.class);
             LogCapture errors = new LogCapture(ErrorHandler.class)) {
            for (String assetId : new String[] {"ETHEREUM", "Ethereum", "eth_er", "eth.er", "eth%20er",
                    "%C3%A9ter", "eth%3Fx%3D1", "x".repeat(65)}) {

                assertBody(404, ASSET_NOT_FOUND, getMarketData(assetId));
            }
            assertEquals(List.of(), fake().requests());
            assertEquals("", client.all() + errors.all());
        }

        // O limite de 64 caracteres é aceito (e só então a CoinGecko é chamada).
        String longest = "a-1" + "x".repeat(61);
        fake().reply(Contract.CT19, 200, simplePriceBody(longest, "1"));
        fake().reply(Contract.CT20, 200, marketChartBody(VALID_PRICES));
        assertEquals(200, getMarketData(longest).statusCode());
        assertEquals(2, fake().requests().size());
    }

    /** A chave de serviço vem antes de tudo: nem o assetId é conferido, nem a CoinGecko chamada. */
    @Test
    void serviceKeyIsCheckedBeforeEverything() throws Exception {
        fake().reply(Contract.CT19, 200, simplePriceBody("ethereum", "1"));
        fake().reply(Contract.CT20, 200, marketChartBody(VALID_PRICES));
        try (LogCapture filter = new LogCapture(ServiceKeyFilter.class)) {
            for (String key : new String[] {null, "", "chave-errada", SERVICE_KEY + "x"}) {
                for (String assetId : new String[] {"ethereum", "ETHEREUM"}) {

                    assertBody(401, SERVICE_KEY_INVALID, app.get("/market-data/" + assetId, key));
                }
            }
            assertEquals(8, filter.lines().size());
            assertTrue(filter.lines().stream().allMatch(
                    line -> line.equals("ERROR Chave de serviço ausente ou incorreta em GET /market-data/{assetId}")));
        }
        assertEquals(List.of(), fake().requests());
    }
}
