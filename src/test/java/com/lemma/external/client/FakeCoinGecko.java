package com.lemma.external.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * CoinGecko falsa (CT19 e CT20) para os testes: servidor HTTP do próprio JDK em
 * 127.0.0.1, porta livre, sem rede externa. A base tem o prefixo /api/v3, como a
 * real, para provar que o cliente o mantém no caminho.
 *
 * A resposta é programada por contrato ({@link #reply}); o padrão é 500 para tudo,
 * para nenhum teste passar sem programar a resposta. {@link #delay} espera antes
 * dos cabeçalhos; {@link #slowBody} manda os cabeçalhos e segura o corpo.
 *
 * Guarda as requisições recebidas: método, caminho, consulta e se o cabeçalho
 * x-cg-demo-api-key veio igual à chave esperada. O valor da chave nunca é
 * guardado, e o falso não loga nada. Use com try-with-resources ou feche no fim.
 */
public final class FakeCoinGecko implements AutoCloseable {

    public static final String BASE_PATH = "/api/v3";

    /** Uma série válida do CT20, com os demais campos que a CoinGecko também manda. */
    public static final String VALID_PRICES = "[[1760875200000,3187.9],[1760878800000,3190.12],[1760882400000,3201]]";

    public enum Contract { CT19, CT20, OTHER }

    /** Uma requisição recebida. apiKeyOk: o cabeçalho veio e é igual à chave esperada. */
    public record Received(Contract contract, String method, String rawPath, String rawQuery, boolean apiKeyOk) {
    }

    private record Reply(int status, String body, long delayMillis, long slowBodyMillis) {
    }

    private static final Pattern MARKET_CHART = Pattern.compile("^" + BASE_PATH + "/coins/[^/]+/market_chart$");
    private static final Reply DEFAULT = new Reply(500, "{\"error\":\"erro interno do falso\"}", 0, 0);

    private final HttpServer server;
    private final ExecutorService threads = Executors.newCachedThreadPool();
    private final List<Received> requests = new CopyOnWriteArrayList<>();
    private final Map<Contract, Reply> replies = new ConcurrentHashMap<>();

    public FakeCoinGecko(String expectedApiKey) {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(threads);
        server.createContext("/", exchange -> handle(exchange, expectedApiKey));
        server.start();
    }

    private void handle(HttpExchange exchange, String expectedApiKey) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            in.readAllBytes();
        }
        Contract contract = contractOf(exchange.getRequestURI().getRawPath());
        requests.add(new Received(
                contract,
                exchange.getRequestMethod(),
                exchange.getRequestURI().getRawPath(),
                exchange.getRequestURI().getRawQuery(),
                expectedApiKey.equals(exchange.getRequestHeaders().getFirst("x-cg-demo-api-key"))));
        // Lê o programado uma vez: um teste que muda a resposta no meio não mistura status e corpo.
        Reply reply = contract == Contract.OTHER ? DEFAULT : replies.getOrDefault(contract, DEFAULT);
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        try {
            if (!sleep(reply.delayMillis())) {
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (reply.slowBodyMillis() > 0) {
                // Corpo em partes (tamanho 0 = chunked): metade agora, o resto depois da espera.
                exchange.sendResponseHeaders(reply.status(), 0);
                OutputStream out = exchange.getResponseBody();
                out.write(bytes, 0, bytes.length / 2);
                out.flush();
                if (!sleep(reply.slowBodyMillis())) {
                    return;
                }
                out.write(bytes, bytes.length / 2, bytes.length - bytes.length / 2);
                out.close();
                return;
            }
            exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
        } finally {
            exchange.close();
        }
    }

    private static boolean sleep(long millis) {
        if (millis <= 0) {
            return true;
        }
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static Contract contractOf(String rawPath) {
        if ((BASE_PATH + "/simple/price").equals(rawPath)) {
            return Contract.CT19;
        }
        return MARKET_CHART.matcher(rawPath).matches() ? Contract.CT20 : Contract.OTHER;
    }

    /** Corpo 200 do CT19: { "{id}": { "usd": preço } }, com o preço JSON como está. */
    public static String simplePriceBody(String assetId, String priceJson) {
        return "{\"" + assetId + "\":{\"usd\":" + priceJson + "}}";
    }

    /** Corpo 200 do CT20 com a lista prices dada como está, mais market_caps e total_volumes. */
    public static String marketChartBody(String pricesJson) {
        return "{\"prices\":" + pricesJson + ",\"market_caps\":[[1760875200000,384000000000.5]],"
                + "\"total_volumes\":[[1760875200000,21000000000.25]]}";
    }

    /** Programa a resposta do contrato para todas as requisições seguintes (mantém as esperas). */
    public FakeCoinGecko reply(Contract contract, int status, String body) {
        Reply current = replies.getOrDefault(contract, DEFAULT);
        replies.put(contract, new Reply(status, body, current.delayMillis(), current.slowBodyMillis()));
        return this;
    }

    /** Espera antes de mandar os cabeçalhos do contrato, para os testes de tempo esgotado. */
    public FakeCoinGecko delay(Contract contract, long millis) {
        Reply current = replies.getOrDefault(contract, DEFAULT);
        replies.put(contract, new Reply(current.status(), current.body(), millis, current.slowBodyMillis()));
        return this;
    }

    /** Manda os cabeçalhos e metade do corpo na hora, e o resto só depois da espera. */
    public FakeCoinGecko slowBody(Contract contract, long millis) {
        Reply current = replies.getOrDefault(contract, DEFAULT);
        replies.put(contract, new Reply(current.status(), current.body(), current.delayMillis(), millis));
        return this;
    }

    /** Cópia das requisições recebidas, na ordem. */
    public List<Received> requests() {
        return List.copyOf(requests);
    }

    /** Base com o prefixo /api/v3 e sem barra final, como a AppConfig entrega. */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + BASE_PATH;
    }

    /** Para o servidor; o shutdownNow interrompe a resposta que estiver esperando. */
    @Override
    public void close() {
        server.stop(0);
        threads.shutdownNow();
    }
}
