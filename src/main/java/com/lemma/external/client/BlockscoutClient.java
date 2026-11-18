package com.lemma.external.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.lemma.external.config.JsonConfig;
import com.lemma.external.dto.AddressTransaction;
import com.lemma.external.dto.AddressTransaction.Direction;
import com.lemma.external.dto.AddressTransaction.Status;
import com.lemma.external.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Cliente do Blockscout público da Sepolia (CT25, seção 7 do desenho), com o
 * HttpClient do JDK e sem chave.
 *
 * Uma tentativa por chamada. Tempos: conexão em até {@link #CONNECT_TIMEOUT} e
 * prazo total da chamada (DNS, conexão, TLS, cabeçalhos e corpo) igual ao
 * responseTimeout (BLOCKSCOUT_TIMEOUT_SECONDS, no máximo 10 s). É a única chamada
 * externa do histórico, então a rota termina em no máximo 10 s, abaixo dos 15 s
 * que o Core API espera.
 *
 * Lê só a primeira página ({"items":[...],"next_page_params":...}, mais recentes
 * primeiro) e devolve até {@link #MAX_TRANSACTIONS} transações mineradas, na ordem
 * recebida. Itens sem bloco (pendentes) ficam de fora. Num item minerado, campo
 * obrigatório ausente ou inválido, ou status desconhecido, invalida a resposta
 * toda: não há resposta parcial.
 *
 * Qualquer falha (status diferente de 200, rede, tempo esgotado, corpo ilegível ou
 * fora do formato) é logada aqui e vira {@link ApiException#historyUnavailable()}.
 * O log traz só o contrato e o status ou o motivo: nunca a URL, o endereço ou o
 * corpo. Das exceções de rede, só o nome da classe (a mensagem pode trazer o host).
 */
public class BlockscoutClient {

    private static final Logger log = LoggerFactory.getLogger(BlockscoutClient.class);

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

    static final String CT25 = "CT25 blockscoutGetAddressTransactions";

    /** Endereço Ethereum: 0x minúsculo e 40 dígitos hexadecimais em qualquer caixa. */
    public static final Pattern ADDRESS = Pattern.compile("^0x[0-9a-fA-F]{40}$");

    static final Pattern TRANSACTION_HASH = Pattern.compile("^0x[0-9a-fA-F]{64}$");

    /** Valor em wei: inteiro não negativo em texto; 78 dígitos cobrem o maior uint256. */
    static final Pattern WEI = Pattern.compile("^[0-9]{1,78}$");

    /** Casas decimais do ETH: 1 ETH = 10^18 wei. */
    static final int ETH_DECIMALS = 18;

    /** Quantas transações mineradas a rota devolve no máximo (a página do Blockscout tem 50). */
    public static final int MAX_TRANSACTIONS = 20;

    private final String baseUrl;
    private final Duration responseTimeout;
    private final HttpClient httpClient;

    /** baseUrl sem barra final (a AppConfig já remove). */
    public BlockscoutClient(String baseUrl, Duration responseTimeout) {
        this.baseUrl = baseUrl;
        this.responseTimeout = responseTimeout;
        // HTTP/1.1: sem tentativa de upgrade para HTTP/2 em http:// simples (testes).
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    /**
     * CT25: GET /addresses/{endereço em minúsculas}/transactions. Devolve até
     * {@link #MAX_TRANSACTIONS} transações mineradas, mais recentes primeiro
     * (lista vazia se o endereço não tiver nenhuma).
     */
    public List<AddressTransaction> addressTransactions(String address) {
        if (address == null || !ADDRESS.matcher(address).matches()) {
            throw new IllegalArgumentException("endereço fora do formato");
        }
        String requested = address.toLowerCase(Locale.ROOT);
        HttpResponse<String> response = send("/addresses/" + requested + "/transactions");
        if (response.statusCode() != 200) {
            log.warn("{}: HTTP {}", CT25, response.statusCode());
            throw ApiException.historyUnavailable();
        }
        JsonNode root = readJson(response.body());
        JsonNode items = root == null || !root.isObject() ? null : root.get("items");
        if (items == null || !items.isArray()) {
            throw outOfFormat();
        }
        List<AddressTransaction> transactions = new ArrayList<>();
        for (JsonNode item : items) {
            if (transactions.size() == MAX_TRANSACTIONS) {
                break;
            }
            if (!item.isObject()) {
                throw outOfFormat();
            }
            JsonNode block = item.get("block_number");
            if (block == null || block.isNull()) {
                continue; // pendente: ainda sem bloco
            }
            AddressTransaction transaction = block.isIntegralNumber() ? readMined(item, requested) : null;
            if (transaction == null) {
                throw outOfFormat();
            }
            transactions.add(transaction);
        }
        return List.copyOf(transactions);
    }

    /** Uma transação minerada; null se faltar campo obrigatório ou algum valor for inválido. */
    private static AddressTransaction readMined(JsonNode item, String requested) {
        String hash = text(item.get("hash"));
        String from = addressOf(item.get("from"));
        String value = text(item.get("value"));
        String timestamp = timestamp(text(item.get("timestamp")));
        Status status = status(text(item.get("status")));
        if (hash == null || !TRANSACTION_HASH.matcher(hash).matches() || from == null
                || value == null || !WEI.matcher(value).matches() || timestamp == null || status == null
                || !item.has("to")) {
            return null;
        }

        // Destino: "to"; na criação de contrato ("to" nulo), o contrato criado, se houver.
        JsonNode toNode = item.get("to");
        String to;
        if (toNode.isNull()) {
            JsonNode created = item.get("created_contract");
            to = created == null || created.isNull() ? null : addressOf(created);
            if (created != null && !created.isNull() && to == null) {
                return null;
            }
        } else {
            to = addressOf(toNode);
            if (to == null) {
                return null;
            }
        }

        boolean sent = from.equalsIgnoreCase(requested);
        boolean received = to != null && to.equalsIgnoreCase(requested);
        Direction direction;
        String counterparty;
        if (sent && received) {
            direction = Direction.SELF;
            counterparty = to;
        } else if (sent) {
            direction = Direction.SENT;
            counterparty = to;
        } else if (received) {
            direction = Direction.RECEIVED;
            counterparty = from;
        } else {
            return null; // o item não envolve o endereço pedido
        }
        return new AddressTransaction(hash, direction, counterparty, weiToEth(value), timestamp, status);
    }

    /**
     * Wei para ETH exato, em notação plana e sem zeros à direita: "1" vira
     * "0.000000000000000001", "500000000000000" vira "0.0005", "0" vira "0".
     * Sem double em nenhum ponto.
     */
    static String weiToEth(String wei) {
        return new BigDecimal(new BigInteger(wei), ETH_DECIMALS).stripTrailingZeros().toPlainString();
    }

    /** ISO 8601 aceito por Instant.parse, normalizado por Instant.toString; senão null. */
    private static String timestamp(String text) {
        if (text == null) {
            return null;
        }
        try {
            return Instant.parse(text).toString();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** "ok" é CONFIRMED, "error" é FAILED; qualquer outro valor (ou ausente) é null. */
    private static Status status(String text) {
        if ("ok".equals(text)) {
            return Status.CONFIRMED;
        }
        if ("error".equals(text)) {
            return Status.FAILED;
        }
        return null;
    }

    /** O campo hash de um objeto de endereço do Blockscout, se for um endereço válido; senão null. */
    private static String addressOf(JsonNode node) {
        String hash = node == null || !node.isObject() ? null : text(node.get("hash"));
        return hash != null && ADDRESS.matcher(hash).matches() ? hash : null;
    }

    /** O texto de um nó textual; número, null ou ausente viram null. */
    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.textValue() : null;
    }

    /**
     * Uma tentativa, com prazo total: o request.timeout sozinho só cobre até os
     * cabeçalhos, então o get com prazo também corta um corpo que chega devagar.
     */
    private HttpResponse<String> send(String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(responseTimeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        CompletableFuture<HttpResponse<String>> future =
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            return future.get(responseTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("{}: tempo esgotado", CT25);
            throw ApiException.historyUnavailable();
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt(); // devolve o pedido de interrupção a quem chamou
            log.warn("{}: chamada interrompida", CT25);
            throw ApiException.historyUnavailable();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof HttpTimeoutException && !(cause instanceof HttpConnectTimeoutException)) {
                log.warn("{}: tempo esgotado", CT25);
            } else {
                // Conexão recusada ou esgotada, host inexistente, TLS, corpo cortado.
                log.warn("{}: falha de rede ({})", CT25,
                        cause == null ? e.getClass().getSimpleName() : cause.getClass().getSimpleName());
            }
            throw ApiException.historyUnavailable();
        }
    }

    private static ApiException outOfFormat() {
        log.warn("{}: HTTP 200 com corpo fora do formato", CT25);
        return ApiException.historyUnavailable();
    }

    /** O corpo como árvore JSON, ou null se for ilegível. Corpo vazio vira MissingNode. */
    private static JsonNode readJson(String body) {
        try {
            return JsonConfig.reader().readTree(body);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
