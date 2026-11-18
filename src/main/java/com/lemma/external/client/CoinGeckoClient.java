package com.lemma.external.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.lemma.external.config.JsonConfig;
import com.lemma.external.dto.PricePoint;
import com.lemma.external.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Cliente da CoinGecko (CT19 e CT20, seção 6 do desenho), com o HttpClient do JDK.
 *
 * Cada chamada envia a chave do plano Demo em x-cg-demo-api-key e tem uma única
 * tentativa. Tempos: conexão em até {@link #CONNECT_TIMEOUT} e prazo total da
 * chamada (DNS, conexão, TLS, cabeçalhos e corpo) igual ao responseTimeout
 * (COINGECKO_TIMEOUT_SECONDS, no máximo 6 s). Com duas chamadas em sequência, o
 * CT18 termina em no máximo 2 × 6 = 12 s, abaixo dos 15 s que o Core API espera.
 *
 * Ativo desconhecido volta como Optional vazio (o service decide o 404); qualquer
 * outra falha (status inesperado, rede, tempo esgotado, corpo ilegível ou fora do
 * formato) é logada aqui e vira {@link ApiException#marketDataUnavailable()}.
 *
 * O log traz só o contrato e o status: nunca a chave, a URL, a consulta, o corpo
 * ou o assetId. Das exceções de rede, só o nome da classe (a mensagem pode trazer
 * o host). Ativo desconhecido não gera log.
 */
public class CoinGeckoClient {

    private static final Logger log = LoggerFactory.getLogger(CoinGeckoClient.class);

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

    static final String API_KEY_HEADER = "x-cg-demo-api-key";

    static final String CT19 = "CT19 coingeckoGetSimplePrice";
    static final String CT20 = "CT20 coingeckoGetMarketChart";

    /**
     * assetId válido (seção 6). O service confere antes; aqui é só a garantia de que
     * nada fora disso ("/", "..", "?", "&", "%") entra no caminho ou na consulta.
     */
    public static final Pattern ASSET_ID = Pattern.compile("^[a-z0-9-]{1,64}$");

    /**
     * Limites de um preço, os mesmos que o Core API aceita: até 30 casas e até 20
     * dígitos inteiros. Recusam também expoentes absurdos (1E+999999999,
     * 1E-999999999), que a escrita em notação plana não conseguiria serializar.
     */
    static final int MAX_PRICE_SCALE = 30;
    static final int MAX_PRICE_INTEGER_DIGITS = 20;

    private final String baseUrl;
    private final String apiKey;
    private final Duration responseTimeout;
    private final HttpClient httpClient;

    /** baseUrl sem barra final (a AppConfig já remove). */
    public CoinGeckoClient(String baseUrl, String apiKey, Duration responseTimeout) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.responseTimeout = responseTimeout;
        // HTTP/1.1: sem tentativa de upgrade para HTTP/2 em http:// simples (testes).
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    /**
     * CT19: GET /simple/price?ids={assetId}&vs_currencies=usd → { "{assetId}": { "usd": preço } }.
     * Objeto sem o id (por exemplo {}) é ativo desconhecido: Optional vazio.
     */
    public Optional<BigDecimal> simplePrice(String assetId) {
        requireValid(assetId);
        HttpResponse<String> response = send(CT19, "/simple/price?ids=" + assetId + "&vs_currencies=usd");
        if (response.statusCode() != 200) {
            throw unexpectedStatus(CT19, response.statusCode());
        }
        JsonNode root = readJson(response.body());
        if (root == null || !root.isObject()) {
            throw outOfFormat(CT19);
        }
        JsonNode entry = root.get(assetId);
        if (entry == null) {
            return Optional.empty();
        }
        BigDecimal price = entry.isObject() ? readPrice(entry.get("usd")) : null;
        if (price == null) {
            throw outOfFormat(CT19);
        }
        return Optional.of(price);
    }

    /**
     * CT20: GET /coins/{assetId}/market_chart?vs_currency=usd&days=7 →
     * { "prices": [ [ms, preço], ... ], ... }. HTTP 404 é ativo desconhecido:
     * Optional vazio. A lista não pode ser vazia; cada item é um par com o
     * instante em milissegundos (inteiro) e um preço válido. Um item inválido
     * invalida tudo. A série segue a ordem recebida; os demais campos são ignorados.
     */
    public Optional<List<PricePoint>> marketChart(String assetId) {
        requireValid(assetId);
        HttpResponse<String> response = send(CT20, "/coins/" + assetId + "/market_chart?vs_currency=usd&days=7");
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            throw unexpectedStatus(CT20, response.statusCode());
        }
        JsonNode root = readJson(response.body());
        JsonNode prices = root == null || !root.isObject() ? null : root.get("prices");
        if (prices == null || !prices.isArray() || prices.isEmpty()) {
            throw outOfFormat(CT20);
        }
        List<PricePoint> series = new ArrayList<>(prices.size());
        for (JsonNode pair : prices) {
            PricePoint point = readPoint(pair);
            if (point == null) {
                throw outOfFormat(CT20);
            }
            series.add(point);
        }
        return Optional.of(List.copyOf(series));
    }

    /** [ms, preço]: exatamente dois números, ms inteiro em long e preço válido; senão null. */
    private static PricePoint readPoint(JsonNode pair) {
        if (!pair.isArray() || pair.size() != 2) {
            return null;
        }
        JsonNode millis = pair.get(0);
        if (!millis.isIntegralNumber() || !millis.canConvertToLong()) {
            return null;
        }
        BigDecimal price = readPrice(pair.get(1));
        if (price == null) {
            return null;
        }
        return new PricePoint(Instant.ofEpochMilli(millis.longValue()).toString(), price);
    }

    /**
     * Um preço: número JSON (nunca texto), não negativo, com até
     * {@link #MAX_PRICE_SCALE} casas e até {@link #MAX_PRICE_INTEGER_DIGITS}
     * dígitos inteiros (precision - scale, em long: com escala negativa, como 1E+2,
     * a conta em int poderia estourar). Devolve o valor exato; ou null.
     */
    private static BigDecimal readPrice(JsonNode node) {
        if (node == null || !node.isNumber()) {
            return null;
        }
        BigDecimal price = node.decimalValue();
        long integerDigits = (long) price.precision() - price.scale();
        if (price.signum() < 0 || price.scale() > MAX_PRICE_SCALE || integerDigits > MAX_PRICE_INTEGER_DIGITS) {
            return null;
        }
        return price;
    }

    private static void requireValid(String assetId) {
        if (assetId == null || !ASSET_ID.matcher(assetId).matches()) {
            throw new IllegalArgumentException("assetId fora do formato");
        }
    }

    /**
     * Uma tentativa, com prazo total: o request.timeout sozinho só cobre até os
     * cabeçalhos, então o get com prazo também corta um corpo que chega devagar.
     */
    private HttpResponse<String> send(String contract, String pathAndQuery) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + pathAndQuery))
                .timeout(responseTimeout)
                .header(API_KEY_HEADER, apiKey)
                .header("Accept", "application/json")
                .GET()
                .build();
        CompletableFuture<HttpResponse<String>> future =
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            return future.get(responseTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("{}: tempo esgotado", contract);
            throw ApiException.marketDataUnavailable();
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt(); // devolve o pedido de interrupção a quem chamou
            log.warn("{}: chamada interrompida", contract);
            throw ApiException.marketDataUnavailable();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof HttpTimeoutException && !(cause instanceof HttpConnectTimeoutException)) {
                log.warn("{}: tempo esgotado", contract);
            } else {
                // Conexão recusada ou esgotada, host inexistente, TLS, corpo cortado.
                log.warn("{}: falha de rede ({})", contract,
                        cause == null ? e.getClass().getSimpleName() : cause.getClass().getSimpleName());
            }
            throw ApiException.marketDataUnavailable();
        }
    }

    /** Status inesperado: 401 (chave recusada) é erro de configuração, ERROR; os demais, WARN. */
    private static ApiException unexpectedStatus(String contract, int status) {
        if (status == 401) {
            log.error("{}: HTTP 401; a CoinGecko recusou a chave", contract);
        } else {
            log.warn("{}: HTTP {}", contract, status);
        }
        return ApiException.marketDataUnavailable();
    }

    private static ApiException outOfFormat(String contract) {
        log.warn("{}: HTTP 200 com corpo fora do formato", contract);
        return ApiException.marketDataUnavailable();
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
