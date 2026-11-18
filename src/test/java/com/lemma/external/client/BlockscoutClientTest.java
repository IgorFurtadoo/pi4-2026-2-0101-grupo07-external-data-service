package com.lemma.external.client;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lemma.external.LogCapture;
import com.lemma.external.client.FakeBlockscout.Received;
import com.lemma.external.dto.AddressTransaction;
import com.lemma.external.dto.AddressTransaction.Direction;
import com.lemma.external.dto.AddressTransaction.Status;
import com.lemma.external.exception.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

import static com.lemma.external.client.FakeBlockscout.addressObject;
import static com.lemma.external.client.FakeBlockscout.minedItem;
import static com.lemma.external.client.FakeBlockscout.page;
import static com.lemma.external.client.FakeBlockscout.pendingItem;
import static com.lemma.external.client.FakeBlockscout.txHash;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** BlockscoutClient (CT25) contra o Blockscout falso, sem rede externa. */
class BlockscoutClientTest {

    /** Endereços em caixa mista, como o Blockscout manda (dados públicos da Sepolia). */
    static final String ME = "0xbdd26D8f9B9876D59CD6ebE1F25a1286b8ECaCD5";
    static final String OTHER = "0x6bdB5129e34773c822044a2078e6ba1576A258E6";
    static final String CONTRACT = "0x420e8CCbC688250a565eF2dc6c1C661067F06BE3";

    /** Formato real do Blockscout (microssegundos) e a forma normalizada que a rota devolve. */
    static final String TS = "2026-11-16T04:12:00.000000Z";
    static final String TS_OUT = "2026-11-16T04:12:00Z";

    private static final String FORMAT = "WARN CT25 blockscoutGetAddressTransactions: HTTP 200 com corpo fora do formato";

    private FakeBlockscout fake;
    private BlockscoutClient client;

    @BeforeEach
    void start() {
        fake = new FakeBlockscout();
        client = new BlockscoutClient(fake.baseUrl(), Duration.ofSeconds(2));
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    /** 502 HISTORY_UNAVAILABLE com exatamente esta linha de log. */
    private static void assertUnavailable(String expectedLog, Executable call) {
        try (LogCapture logs = new LogCapture(BlockscoutClient.class)) {
            ApiException e = assertThrows(ApiException.class, call);
            assertEquals(502, e.getStatus());
            assertEquals("HISTORY_UNAVAILABLE", e.getCode());
            assertEquals(expectedLog, logs.all());
        }
    }

    private static AddressTransaction tx(int n, Direction direction, String counterparty, String amountEth,
                                         Status status) {
        return new AddressTransaction(txHash(n), direction, counterparty, amountEth, TS_OUT, status);
    }

    // ---------- requisição ----------

    @Test
    void sendsGetToTheAddressPathInLowerCaseWithoutKeyOrQuery() {
        fake.reply(200, page());

        client.addressTransactions(ME);

        List<Received> requests = fake.requests();
        assertEquals(1, requests.size());
        Received received = requests.get(0);
        assertEquals("GET", received.method());
        assertEquals("/api/v2/addresses/" + ME.toLowerCase(Locale.ROOT) + "/transactions", received.rawPath());
        assertEquals(null, received.rawQuery());
        // Só cabeçalhos do próprio HTTP: nada de chave (Blockscout público sem chave).
        assertTrue(Set.of("accept", "host", "user-agent", "connection", "content-length")
                .containsAll(received.headerNames()), received.headerNames().toString());
        for (String key : new String[] {"x-cg-demo-api-key", "x-service-key", "authorization", "x-api-key", "apikey"}) {
            assertFalse(received.headerNames().contains(key), key);
        }
    }

    // ---------- conversão ----------

    /** Direção pela comparação sem caixa, com o endereço pedido em outra caixa que a do Blockscout. */
    @Test
    void mapsDirectionIgnoringCase() {
        fake.reply(200, page(
                minedItem(txHash(1), ME.toUpperCase(Locale.ROOT).replace("0X", "0x"), OTHER, "1000", "ok", TS),
                minedItem(txHash(2), OTHER, ME.toLowerCase(Locale.ROOT), "2000", "ok", TS),
                minedItem(txHash(3), ME, ME.toLowerCase(Locale.ROOT), "3000", "ok", TS)));
        String requested = "0x" + ME.substring(2).toUpperCase(Locale.ROOT);

        assertEquals(List.of(
                tx(1, Direction.SENT, OTHER, "0.000000000000001", Status.CONFIRMED),
                tx(2, Direction.RECEIVED, OTHER, "0.000000000000002", Status.CONFIRMED),
                tx(3, Direction.SELF, ME.toLowerCase(Locale.ROOT), "0.000000000000003", Status.CONFIRMED)),
                client.addressTransactions(requested));
    }

    @Test
    void weiToEthIsExactPlainText() {
        String[][] cases = {
                {"1", "0.000000000000000001"},
                {"500000000000000", "0.0005"},
                {"0", "0"},
                {"000", "0"},
                {"1000000000000000000", "1"},
                {"1234567890123456789", "1.234567890123456789"},
                {"123456789012345678901234567890", "123456789012.34567890123456789"},
                {"115792089237316195423570985008687907853269984665640564039457584007913129639935",
                        "115792089237316195423570985008687907853269984665640564039457.584007913129639935"}};
        for (String[] c : cases) {
            assertEquals(c[1], BlockscoutClient.weiToEth(c[0]), c[0]);
        }

        fake.reply(200, page(
                minedItem(txHash(1), OTHER, ME, "1", "ok", TS),
                minedItem(txHash(2), OTHER, ME, "500000000000000", "ok", TS),
                minedItem(txHash(3), OTHER, ME, "1234567890123456789", "ok", TS),
                minedItem(txHash(4), OTHER, ME, "0", "ok", TS)));
        assertEquals(List.of("0.000000000000000001", "0.0005", "1.234567890123456789", "0"),
                client.addressTransactions(ME).stream().map(AddressTransaction::amountEth).toList());
    }

    /** status "error" é FAILED qualquer que seja o result (os dois valores vistos no Blockscout real). */
    @Test
    void errorStatusIsFailed() {
        ObjectNode awaiting = minedItem(txHash(2), ME, OTHER, "0", "error", TS);
        awaiting.put("result", "awaiting_internal_transactions");
        fake.reply(200, page(minedItem(txHash(1), ME, OTHER, "1000000000000000", "error", TS), awaiting));

        assertEquals(List.of(
                tx(1, Direction.SENT, OTHER, "0.001", Status.FAILED),
                tx(2, Direction.SENT, OTHER, "0", Status.FAILED)),
                client.addressTransactions(ME));
    }

    @Test
    void timestampIsNormalizedWithInstant() {
        String[][] cases = {
                {"2026-11-16T04:12:00.000000Z", "2026-11-16T04:12:00Z"},
                {"2026-11-16T04:12:00Z", "2026-11-16T04:12:00Z"},
                {"2026-11-16T04:12:00.123400Z", "2026-11-16T04:12:00.123400Z"},
                {"2026-11-16T04:12:00.500Z", "2026-11-16T04:12:00.500Z"}};
        for (String[] c : cases) {
            fake.reply(200, page(minedItem(txHash(1), OTHER, ME, "1", "ok", c[0])));

            assertEquals(c[1], client.addressTransactions(ME).get(0).timestamp(), c[0]);
        }
    }

    /** Itens sem bloco (pendentes, como o Blockscout real os manda) ficam de fora, em qualquer posição. */
    @Test
    void pendingItemsAreLeftOut() {
        fake.reply(200, page(
                pendingItem(txHash(9), ME, OTHER, "5"),
                minedItem(txHash(1), OTHER, ME, "1", "ok", TS),
                pendingItem(txHash(8), OTHER, ME, "5")));
        ObjectNode withoutBlock = pendingItem(txHash(7), ME, OTHER, "5");
        withoutBlock.remove("block_number");

        assertEquals(List.of(tx(1, Direction.RECEIVED, OTHER, "0.000000000000000001", Status.CONFIRMED)),
                client.addressTransactions(ME));

        fake.reply(200, page(withoutBlock));
        assertEquals(List.of(), client.addressTransactions(ME));
    }

    @Test
    void emptyListIsEmpty() {
        fake.reply(200, "{\"items\":[],\"next_page_params\":null}");   // corpo real de um endereço sem transações

        assertEquals(List.of(), client.addressTransactions(ME));
    }

    /**
     * Página cheia (50 itens, como a real), com pendentes no meio: as 20 primeiras
     * mineradas, na ordem recebida. O que vem depois da 20ª não é lido, nem se
     * estiver fora do formato. next_page_params é ignorado.
     */
    @Test
    void returnsTheFirstTwentyMinedInOrder() {
        List<ObjectNode> items = new ArrayList<>();
        for (int n = 1; n <= 50; n++) {
            items.add(n % 5 == 0 ? pendingItem(txHash(n), ME, OTHER, "1") : minedItem(txHash(n), OTHER, ME, "1", "ok", TS));
        }
        items.get(40).remove("hash");
        String body = page(items).replace("\"next_page_params\":null",
                "\"next_page_params\":{\"block_number\":12094522,\"index\":85,\"items_count\":50}");
        fake.reply(200, body);

        List<String> hashes = client.addressTransactions(ME).stream().map(AddressTransaction::hash).toList();

        List<String> expected = new ArrayList<>();
        for (int n = 1; expected.size() < 20; n++) {
            if (n % 5 != 0) {
                expected.add(txHash(n));
            }
        }
        assertEquals(expected, hashes);
        assertEquals(1, fake.calls());   // só a primeira página
    }

    /** Criação de contrato (to nulo): o destino é o contrato criado; sem contrato, contraparte nula. */
    @Test
    void contractCreationUsesTheCreatedContract() {
        ObjectNode deploy = minedItem(txHash(1), ME, null, "0", "ok", TS);
        deploy.set("created_contract", addressObject(CONTRACT));
        ObjectNode failedDeploy = minedItem(txHash(2), ME, null, "0", "error", TS);
        fake.reply(200, page(deploy, failedDeploy));

        assertEquals(List.of(
                tx(1, Direction.SENT, CONTRACT, "0", Status.CONFIRMED),
                tx(2, Direction.SENT, null, "0", Status.FAILED)),
                client.addressTransactions(ME));

        // O próprio contrato pedindo o histórico: a criação chega como recebida, do criador.
        fake.reply(200, page(deploy));
        assertEquals(List.of(tx(1, Direction.RECEIVED, ME, "0", Status.CONFIRMED)),
                client.addressTransactions(CONTRACT.toLowerCase(Locale.ROOT)));
    }

    // ---------- falhas ----------

    @Test
    void errorStatusIs502() {
        for (int status : new int[] {429, 500, 502, 503, 404, 422}) {
            fake.reply(status, "{\"message\":\"corpo-que-nao-vai-ao-log\"}");

            assertUnavailable("WARN CT25 blockscoutGetAddressTransactions: HTTP " + status,
                    () -> client.addressTransactions(ME));
        }
    }

    @Test
    void timeoutIs502WithinTheDeadline() {
        BlockscoutClient fast = new BlockscoutClient(fake.baseUrl(), Duration.ofMillis(300));
        fake.reply(200, page()).delay(3_000);

        assertTimeoutPreemptively(Duration.ofMillis(1_500), () -> assertUnavailable(
                "WARN CT25 blockscoutGetAddressTransactions: tempo esgotado", () -> fast.addressTransactions(ME)));
    }

    /** O prazo é total: cabeçalhos na hora e corpo devagar também esgota o tempo. */
    @Test
    void slowBodyIs502WithinTheDeadline() {
        BlockscoutClient fast = new BlockscoutClient(fake.baseUrl(), Duration.ofMillis(300));
        fake.reply(200, page(minedItem(txHash(1), OTHER, ME, "1", "ok", TS))).slowBody(3_000);

        assertTimeoutPreemptively(Duration.ofMillis(1_500), () -> assertUnavailable(
                "WARN CT25 blockscoutGetAddressTransactions: tempo esgotado", () -> fast.addressTransactions(ME)));
    }

    @Test
    void connectionRefusedIs502() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        BlockscoutClient offline = new BlockscoutClient("http://127.0.0.1:" + closedPort + "/api/v2",
                Duration.ofSeconds(2));

        assertUnavailable("WARN CT25 blockscoutGetAddressTransactions: falha de rede (ConnectException)",
                () -> offline.addressTransactions(ME));
    }

    @Test
    void unreadableOrOutOfFormatBodyIs502() {
        for (String body : new String[] {"isto não é json", "", "[]", "{}", "{\"items\":null}",
                "{\"items\":{}}", "{\"items\":\"x\"}", "{\"items\":[1]}", "{\"items\":[null]}"}) {
            fake.reply(200, body);

            assertUnavailable(FORMAT, () -> client.addressTransactions(ME));
        }
    }

    /** Um item minerado ruim invalida a resposta toda, mesmo com itens bons antes e depois. */
    private void assertItemInvalidatesAll(String name, Consumer<ObjectNode> change) {
        ObjectNode bad = minedItem(txHash(2), OTHER, ME, "1", "ok", TS);
        change.accept(bad);
        fake.reply(200, page(minedItem(txHash(1), OTHER, ME, "1", "ok", TS), bad,
                minedItem(txHash(3), ME, OTHER, "1", "ok", TS)));

        try (LogCapture logs = new LogCapture(BlockscoutClient.class)) {
            ApiException e = assertThrows(ApiException.class, () -> client.addressTransactions(ME), name);
            assertEquals("HISTORY_UNAVAILABLE", e.getCode(), name);
            assertEquals(FORMAT, logs.all(), name);
        }
    }

    @Test
    void missingRequiredFieldInMinedItemIs502() {
        for (String field : new String[] {"hash", "from", "value", "timestamp", "status", "to"}) {
            assertItemInvalidatesAll("sem " + field, item -> item.remove(field));
            if (!field.equals("to")) {
                assertItemInvalidatesAll(field + " nulo", item -> item.putNull(field));
            }
        }
        assertItemInvalidatesAll("from sem hash", item -> ((ObjectNode) item.get("from")).remove("hash"));
        assertItemInvalidatesAll("to sem hash", item -> ((ObjectNode) item.get("to")).remove("hash"));
    }

    @Test
    void invalidValueInMinedItemIs502() {
        for (String value : new String[] {"-1", "+1", "1.5", "1e18", "0x10", "", " 1", "1".repeat(79)}) {
            assertItemInvalidatesAll("value " + value, item -> item.put("value", value));
        }
        assertItemInvalidatesAll("value numérico", item -> item.put("value", 1000));
        assertItemInvalidatesAll("block_number em texto", item -> item.put("block_number", "12144876"));
        assertItemInvalidatesAll("block_number decimal", item -> item.put("block_number", 1.5));
        assertItemInvalidatesAll("hash curto", item -> item.put("hash", "0x1234"));
        assertItemInvalidatesAll("hash não hex", item -> item.put("hash", "0x" + "g".repeat(64)));
        assertItemInvalidatesAll("from em texto", item -> item.put("from", OTHER));
        assertItemInvalidatesAll("from inválido", item -> item.set("from", addressObject("0x123")));
        assertItemInvalidatesAll("to inválido", item -> item.set("to", addressObject("0x" + "z".repeat(40))));
        assertItemInvalidatesAll("created_contract sem hash", item -> {
            item.putNull("to");
            item.putObject("created_contract");
        });
        for (String timestamp : new String[] {"", "16/11/2026", "2026-11-16 04:12:00", "2026-11-16T04:12:00",
                "2026-13-16T04:12:00Z"}) {
            assertItemInvalidatesAll("timestamp " + timestamp, item -> item.put("timestamp", timestamp));
        }
        assertItemInvalidatesAll("timestamp numérico", item -> item.put("timestamp", 1763266320));
    }

    /** Status fora de "ok" e "error" num item minerado: 502, sem esconder o item em silêncio. */
    @Test
    void unknownStatusInMinedItemIs502() {
        for (String status : new String[] {"pending", "OK", "Error", "success", "failed", ""}) {
            assertItemInvalidatesAll("status " + status, item -> item.put("status", status));
        }
    }

    @Test
    void itemThatDoesNotInvolveTheAddressIs502() {
        assertItemInvalidatesAll("sem relação", item -> {
            item.set("from", addressObject(OTHER));
            item.set("to", addressObject(CONTRACT));
        });
    }

    /** O log de todas as falhas nunca traz o endereço, a URL nem o corpo. */
    @Test
    void logNeverContainsAddressUrlOrBody() {
        try (LogCapture logs = new LogCapture(BlockscoutClient.class)) {
            fake.reply(429, "corpo-secreto");
            assertThrows(ApiException.class, () -> client.addressTransactions(ME));
            fake.reply(200, "{\"items\":\"corpo-secreto\"}");
            assertThrows(ApiException.class, () -> client.addressTransactions(ME));
            ObjectNode bad = minedItem(txHash(1), OTHER, ME, "1", "corpo-secreto", TS);
            fake.reply(200, page(bad));
            assertThrows(ApiException.class, () -> client.addressTransactions(ME));

            String all = logs.all();
            assertEquals(3, logs.lines().size(), all);
            for (String forbidden : new String[] {ME, ME.toLowerCase(Locale.ROOT), ME.substring(2, 12), OTHER,
                    "127.0.0.1", "/api/v2", "addresses", "corpo-secreto"}) {
                assertFalse(all.contains(forbidden), forbidden);
            }
        }
    }

    @Test
    void addressOutsideTheFormatNeverReachesTheUrl() {
        for (String address : new String[] {null, "", "0x123", ME.substring(2), "0X" + ME.substring(2),
                ME + "0", ME.substring(0, 41), "0x" + "g".repeat(40), ME + "/../x", ME + "?x=1", ME + "\n"}) {
            assertThrows(IllegalArgumentException.class, () -> client.addressTransactions(address), address);
        }
        assertEquals(0, fake.calls());
    }
}
