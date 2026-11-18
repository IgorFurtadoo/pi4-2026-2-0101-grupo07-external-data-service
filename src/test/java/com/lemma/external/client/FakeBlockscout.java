package com.lemma.external.client;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Blockscout falso (CT25) para os testes: servidor HTTP do próprio JDK em
 * 127.0.0.1, porta livre, sem rede externa. A base tem o prefixo /api/v2, como a
 * real, para provar que o cliente o mantém no caminho.
 *
 * A resposta de GET .../addresses/{endereço}/transactions é programada com
 * {@link #reply}; o padrão é 500 para tudo, para nenhum teste passar sem programar
 * a resposta. {@link #delay} espera antes dos cabeçalhos; {@link #slowBody} manda
 * os cabeçalhos e segura o corpo.
 *
 * Guarda as requisições recebidas: método, caminho, consulta e os nomes (em
 * minúsculas) dos cabeçalhos, para provar que nenhuma chave é enviada. Use com
 * try-with-resources ou feche no fim.
 *
 * Os construtores de corpo ({@link #minedItem}, {@link #page}) imitam o formato
 * real conferido em 18/11/2026, com campos que o cliente ignora.
 */
public final class FakeBlockscout implements AutoCloseable {

    public static final String BASE_PATH = "/api/v2";

    /** Uma requisição recebida. */
    public record Received(String method, String rawPath, String rawQuery, Set<String> headerNames) {
    }

    private record Reply(int status, String body, long delayMillis, long slowBodyMillis) {
    }

    private static final Pattern TRANSACTIONS = Pattern.compile("^" + BASE_PATH + "/addresses/[^/]+/transactions$");
    private static final Reply DEFAULT = new Reply(500, "{\"message\":\"erro interno do falso\"}", 0, 0);

    private final HttpServer server;
    private final ExecutorService threads = Executors.newCachedThreadPool();
    private final List<Received> requests = new CopyOnWriteArrayList<>();
    private volatile Reply reply = DEFAULT;

    public FakeBlockscout() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(threads);
        server.createContext("/", this::handle);
        server.start();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            in.readAllBytes();
        }
        String rawPath = exchange.getRequestURI().getRawPath();
        requests.add(new Received(
                exchange.getRequestMethod(),
                rawPath,
                exchange.getRequestURI().getRawQuery(),
                exchange.getRequestHeaders().keySet().stream()
                        .map(name -> name.toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet())));
        // Lê o programado uma vez: um teste que muda a resposta no meio não mistura status e corpo.
        Reply current = TRANSACTIONS.matcher(rawPath).matches() ? reply : DEFAULT;
        byte[] bytes = current.body().getBytes(StandardCharsets.UTF_8);
        try {
            if (!sleep(current.delayMillis())) {
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            if (current.slowBodyMillis() > 0) {
                // Corpo em partes (tamanho 0 = chunked): metade agora, o resto depois da espera.
                exchange.sendResponseHeaders(current.status(), 0);
                OutputStream out = exchange.getResponseBody();
                out.write(bytes, 0, bytes.length / 2);
                out.flush();
                if (!sleep(current.slowBodyMillis())) {
                    return;
                }
                out.write(bytes, bytes.length / 2, bytes.length - bytes.length / 2);
                out.close();
                return;
            }
            exchange.sendResponseHeaders(current.status(), bytes.length == 0 ? -1 : bytes.length);
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

    // ---------- corpos ----------

    /** Hash de transação válido e distinto para cada n. */
    public static String txHash(int n) {
        return String.format("0x%064x", n);
    }

    /** Objeto de endereço como o Blockscout manda ({hash, is_contract, name, ...}). */
    public static ObjectNode addressObject(String hash) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("hash", hash);
        node.put("is_contract", false);
        node.putNull("name");
        node.putArray("public_tags");
        return node;
    }

    /**
     * Item minerado no formato real: block_number, status, result, value (wei em
     * texto), timestamp com microssegundos, from e to como objetos, mais campos que
     * o cliente ignora. to null monta a criação de contrato sem contrato criado.
     */
    public static ObjectNode minedItem(String hash, String from, String to, String valueWei, String status,
                                       String timestamp) {
        ObjectNode item = JsonNodeFactory.instance.objectNode();
        item.put("hash", hash);
        item.put("block_number", 12144876);
        item.put("status", status);
        item.put("result", "ok".equals(status) ? "success" : "Out of gas");
        item.put("value", valueWei);
        item.put("timestamp", timestamp);
        item.set("from", addressObject(from));
        if (to == null) {
            item.putNull("to");
        } else {
            item.set("to", addressObject(to));
        }
        item.putNull("created_contract");
        item.putObject("fee").put("type", "actual").put("value", "357000");
        item.put("gas_used", "21000");
        item.put("nonce", 1);
        item.putArray("transaction_types").add("coin_transfer");
        return item;
    }

    /** Item pendente como o real: block_number, status e timestamp nulos, result "pending". */
    public static ObjectNode pendingItem(String hash, String from, String to, String valueWei) {
        ObjectNode item = minedItem(hash, from, to, valueWei, "ok", "2026-11-18T04:40:16.000000Z");
        item.putNull("block_number");
        item.putNull("status");
        item.put("result", "pending");
        item.putNull("timestamp");
        return item;
    }

    /** Primeira página: {"items":[...],"next_page_params":null}. */
    public static String page(ObjectNode... items) {
        return page(List.of(items));
    }

    public static String page(List<ObjectNode> items) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ArrayNode array = root.putArray("items");
        items.forEach(array::add);
        root.putNull("next_page_params");
        return root.toString();
    }

    // ---------- programação e consulta ----------

    /** Programa a resposta para todas as requisições seguintes (mantém as esperas). */
    public FakeBlockscout reply(int status, String body) {
        reply = new Reply(status, body, reply.delayMillis(), reply.slowBodyMillis());
        return this;
    }

    /** Espera antes de mandar os cabeçalhos, para os testes de tempo esgotado. */
    public FakeBlockscout delay(long millis) {
        reply = new Reply(reply.status(), reply.body(), millis, reply.slowBodyMillis());
        return this;
    }

    /** Manda os cabeçalhos e metade do corpo na hora, e o resto só depois da espera. */
    public FakeBlockscout slowBody(long millis) {
        reply = new Reply(reply.status(), reply.body(), reply.delayMillis(), millis);
        return this;
    }

    /** Cópia das requisições recebidas, na ordem. */
    public List<Received> requests() {
        return List.copyOf(requests);
    }

    /** Quantas requisições chegaram até agora. */
    public int calls() {
        return requests.size();
    }

    /** Base com o prefixo /api/v2 e sem barra final, como a AppConfig entrega. */
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
