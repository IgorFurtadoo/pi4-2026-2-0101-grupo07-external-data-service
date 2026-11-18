package com.lemma.external;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lemma.external.client.BlockscoutClient;
import com.lemma.external.client.FakeBlockscout;
import com.lemma.external.controller.ErrorHandler;
import com.lemma.external.security.ServiceKeyFilter;
import com.lemma.external.service.HistoryCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

import static com.lemma.external.TestApp.HISTORY_CACHE;
import static com.lemma.external.TestApp.SERVICE_KEY;
import static com.lemma.external.client.FakeBlockscout.minedItem;
import static com.lemma.external.client.FakeBlockscout.page;
import static com.lemma.external.client.FakeBlockscout.pendingItem;
import static com.lemma.external.client.FakeBlockscout.txHash;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CT24 GET /addresses/{ethereumAddress}/transactions por HTTP real, com o Blockscout falso. */
class AddressHistoryRouteTest {

    private static final String ME = "0xbdd26D8f9B9876D59CD6ebE1F25a1286b8ECaCD5";
    private static final String OTHER = "0x6bdB5129e34773c822044a2078e6ba1576A258E6";
    private static final String TS = "2026-11-16T04:12:00.000000Z";

    private static final String ADDRESS_INVALID =
            "{\"code\":\"ADDRESS_INVALID\",\"message\":\"Endereço Ethereum inválido.\"}";
    private static final String UNAVAILABLE =
            "{\"code\":\"HISTORY_UNAVAILABLE\",\"message\":\"Histórico de transações indisponível.\"}";
    private static final String SERVICE_KEY_INVALID =
            "{\"code\":\"SERVICE_KEY_INVALID\",\"message\":\"Chave de serviço ausente ou incorreta.\"}";

    private final TestApp app = new TestApp().start();

    @AfterEach
    void stop() {
        app.stop();
    }

    private FakeBlockscout fake() {
        return app.blockscout;
    }

    private HttpResponse<String> getHistory(String address) throws Exception {
        return app.get("/addresses/" + address + "/transactions", SERVICE_KEY);
    }

    private static void assertBody(int status, String body, HttpResponse<String> response) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(body, response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
    }

    private static String emptyBody(String address) {
        return "{\"ethereumAddress\":\"" + address + "\",\"network\":\"SEPOLIA\",\"transactions\":[]}";
    }

    private static String address(int n) {
        return String.format("0x%040x", n);
    }

    @Test
    void returnsTheHistoryExactly() throws Exception {
        ObjectNode failedDeploy = minedItem(txHash(3), ME, null, "0", "error", "2026-11-09T02:26:24.000000Z");
        fake().reply(200, page(
                pendingItem(txHash(9), ME, OTHER, "1"),
                minedItem(txHash(1), OTHER, ME, "500000000000000", "ok", TS),
                minedItem(txHash(2), ME.toLowerCase(Locale.ROOT), OTHER, "123456789012345678901234567890", "error",
                        "2026-11-15T19:50:48.000000Z"),
                failedDeploy));

        HttpResponse<String> response = getHistory(ME);

        assertBody(200, "{\"ethereumAddress\":\"" + ME + "\",\"network\":\"SEPOLIA\",\"transactions\":["
                + "{\"hash\":\"" + txHash(1) + "\",\"direction\":\"RECEIVED\",\"counterpartyAddress\":\"" + OTHER
                + "\",\"amountEth\":\"0.0005\",\"timestamp\":\"2026-11-16T04:12:00Z\",\"status\":\"CONFIRMED\"},"
                + "{\"hash\":\"" + txHash(2) + "\",\"direction\":\"SENT\",\"counterpartyAddress\":\"" + OTHER
                + "\",\"amountEth\":\"123456789012.34567890123456789\",\"timestamp\":\"2026-11-15T19:50:48Z\","
                + "\"status\":\"FAILED\"},"
                + "{\"hash\":\"" + txHash(3) + "\",\"direction\":\"SENT\",\"counterpartyAddress\":null,"
                + "\"amountEth\":\"0\",\"timestamp\":\"2026-11-09T02:26:24Z\",\"status\":\"FAILED\"}]}", response);
        assertEquals(1, fake().calls());
    }

    @Test
    void addressWithoutTransactionsIsAnEmptyList() throws Exception {
        fake().reply(200, "{\"items\":[],\"next_page_params\":null}");

        assertBody(200, emptyBody(ME), getHistory(ME));
    }

    /** Fora de ^0x[0-9a-fA-F]{40}$: 400 sem chamar o Blockscout e sem log. */
    @Test
    void addressOutsideTheFormatIs400WithoutCallingBlockscout() throws Exception {
        fake().reply(200, page());
        try (LogCapture client = new LogCapture(BlockscoutClient.class);
             LogCapture errors = new LogCapture(ErrorHandler.class)) {
            for (String address : new String[] {"0x123", ME.substring(2), "0X" + ME.substring(2), ME + "0",
                    ME.substring(0, 41), "0x" + "g".repeat(40), ME + "%0A", "%20" + ME, ME + "%3Fx%3D1",
                    "0x%C3%A9" + ME.substring(4)}) {

                assertBody(400, ADDRESS_INVALID, getHistory(address));
            }
            assertEquals(0, fake().calls());
            assertEquals("", client.all() + errors.all());
        }
    }

    /** A chave de serviço vem antes de tudo: nem o endereço é conferido, nem o Blockscout chamado. */
    @Test
    void serviceKeyIsCheckedBeforeEverything() throws Exception {
        fake().reply(200, page());
        try (LogCapture filter = new LogCapture(ServiceKeyFilter.class)) {
            for (String key : new String[] {null, "", "chave-errada", SERVICE_KEY + "x"}) {
                for (String address : new String[] {ME, "0x123"}) {

                    assertBody(401, SERVICE_KEY_INVALID, app.get("/addresses/" + address + "/transactions", key));
                }
            }
            assertEquals(8, filter.lines().size());
            assertTrue(filter.lines().stream().allMatch(line -> line.equals(
                    "ERROR Chave de serviço ausente ou incorreta em GET /addresses/{ethereumAddress}/transactions")),
                    filter.all());
        }
        assertEquals(0, fake().calls());
    }

    @Test
    void blockscoutFailureIs502() throws Exception {
        for (int status : new int[] {429, 500, 503}) {
            fake().reply(status, "{}");

            assertBody(502, UNAVAILABLE, getHistory(ME));
        }
        fake().reply(200, "isto não é json");
        assertBody(502, UNAVAILABLE, getHistory(ME));
        ObjectNode noHash = minedItem(txHash(1), OTHER, ME, "1", "ok", TS);
        noHash.remove("hash");
        fake().reply(200, page(minedItem(txHash(2), OTHER, ME, "1", "ok", TS), noHash));
        assertBody(502, UNAVAILABLE, getHistory(ME));
        assertEquals(5, fake().calls());   // nenhuma falha foi guardada no cache
    }

    // ---------- cache ----------

    @Test
    void secondCallWithinTheValidityUsesTheCache() throws Exception {
        fake().reply(200, page(minedItem(txHash(1), OTHER, ME, "1", "ok", TS)));
        String first = getHistory(ME).body();

        app.clock.advance(HISTORY_CACHE.minusMillis(1));
        assertBody(200, first, getHistory(ME));
        assertEquals(1, fake().calls());

        app.clock.advance(Duration.ofMillis(1));   // venceu: chama de novo
        assertBody(200, first, getHistory(ME));
        assertEquals(2, fake().calls());
    }

    /** Outra caixa usa a mesma entrada, e a resposta repete o endereço como foi enviado. */
    @Test
    void sameAddressInAnotherCaseUsesTheSameEntry() throws Exception {
        fake().reply(200, "{\"items\":[],\"next_page_params\":null}");
        String lower = ME.toLowerCase(Locale.ROOT);
        String upper = "0x" + ME.substring(2).toUpperCase(Locale.ROOT);

        assertBody(200, emptyBody(ME), getHistory(ME));
        assertBody(200, emptyBody(lower), getHistory(lower));
        assertBody(200, emptyBody(upper), getHistory(upper));
        assertEquals(1, fake().calls());
    }

    @Test
    void failureIsNotCached() throws Exception {
        fake().reply(503, "{}");
        assertBody(502, UNAVAILABLE, getHistory(ME));

        fake().reply(200, "{\"items\":[],\"next_page_params\":null}");
        assertBody(200, emptyBody(ME), getHistory(ME));
        assertBody(200, emptyBody(ME), getHistory(ME));
        assertEquals(2, fake().calls());
    }

    /** No máximo 100 endereços: o 101º tira o menos usado, que volta a chamar o Blockscout. */
    @Test
    void cacheKeepsAtMostOneHundredAddresses() throws Exception {
        fake().reply(200, "{\"items\":[],\"next_page_params\":null}");
        for (int n = 1; n <= HistoryCache.MAX_ADDRESSES + 1; n++) {
            assertEquals(200, getHistory(address(n)).statusCode());
        }
        assertEquals(101, fake().calls());

        assertEquals(200, getHistory(address(101)).statusCode());   // ainda no cache
        assertEquals(200, getHistory(address(2)).statusCode());     // ainda no cache
        assertEquals(101, fake().calls());

        assertEquals(200, getHistory(address(1)).statusCode());     // saiu: chama de novo
        assertEquals(102, fake().calls());
    }
}
