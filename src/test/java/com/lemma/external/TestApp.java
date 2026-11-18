package com.lemma.external;

import com.lemma.external.client.BlockscoutClient;
import com.lemma.external.client.CoinGeckoClient;
import com.lemma.external.client.FakeBlockscout;
import com.lemma.external.client.FakeCoinGecko;
import com.lemma.external.controller.AddressHistoryController;
import com.lemma.external.controller.MarketDataController;
import com.lemma.external.service.AddressHistoryService;
import com.lemma.external.service.HistoryCache;
import com.lemma.external.service.MarketDataService;
import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * Sobe o serviço numa porta livre de 127.0.0.1 com a mesma configuração do Main
 * (Main.configure), uma chave de serviço de teste, a CoinGecko falsa e o
 * Blockscout falso (servidores HTTP do JDK, com os mesmos clientes do Main). O
 * cache do histórico usa o {@link #clock} de teste, que só anda quando o teste
 * manda. Os testes podem acrescentar rotas próprias. Sem rede externa.
 */
public final class TestApp {

    /** Chave de serviço obviamente falsa, só para os testes. */
    public static final String SERVICE_KEY = "chave-de-servico-de-teste-falsa";
    /** Chave da CoinGecko obviamente falsa, só para os testes. */
    public static final String COINGECKO_API_KEY = "chave-coingecko-de-teste-falsa";
    /** Prazo padrão de cada chamada à CoinGecko nos testes. */
    public static final Duration DEFAULT_COINGECKO_TIMEOUT = Duration.ofSeconds(2);
    /** Prazo da chamada ao Blockscout nos testes. */
    public static final Duration BLOCKSCOUT_TIMEOUT = Duration.ofSeconds(2);
    /** Validade do cache do histórico nos testes (a mesma do .env.example). */
    public static final Duration HISTORY_CACHE = Duration.ofSeconds(12);

    private static final HttpClient http = HttpClient.newHttpClient();

    /** CoinGecko falsa; fechada no stop. */
    public final FakeCoinGecko coinGecko = new FakeCoinGecko(COINGECKO_API_KEY);
    /** Blockscout falso; fechado no stop. */
    public final FakeBlockscout blockscout = new FakeBlockscout();
    /** Relógio do cache do histórico. */
    public final TestClock clock = new TestClock(Instant.parse("2026-11-18T12:00:00Z"));

    private final Duration coinGeckoTimeout;
    private Javalin app;

    public TestApp() {
        this(DEFAULT_COINGECKO_TIMEOUT);
    }

    /** Com um prazo próprio por chamada à CoinGecko, para os testes de tempo esgotado. */
    public TestApp(Duration coinGeckoTimeout) {
        this.coinGeckoTimeout = coinGeckoTimeout;
    }

    public TestApp start() {
        return start(javalin -> {
        });
    }

    /** Sobe com a configuração do Main e, depois dela, as rotas que só existem no teste. */
    public TestApp start(Consumer<JavalinConfig> testRoutes) {
        MarketDataController marketData = new MarketDataController(new MarketDataService(
                new CoinGeckoClient(coinGecko.baseUrl(), COINGECKO_API_KEY, coinGeckoTimeout)));
        AddressHistoryController history = new AddressHistoryController(new AddressHistoryService(
                new BlockscoutClient(blockscout.baseUrl(), BLOCKSCOUT_TIMEOUT),
                new HistoryCache(HISTORY_CACHE, HistoryCache.MAX_ADDRESSES, clock)));
        app = Javalin.create(javalin -> {
            Main.configure(javalin, SERVICE_KEY, marketData, history);
            testRoutes.accept(javalin);
        }).start("127.0.0.1", 0);
        return this;
    }

    public void stop() {
        if (app != null) {
            app.stop();
        }
        coinGecko.close();
        blockscout.close();
    }

    /** GET no caminho, com X-Service-Key se a chave não for null. */
    public HttpResponse<String> get(String path, String serviceKey) throws IOException, InterruptedException {
        return send("GET", path, serviceKey);
    }

    public HttpResponse<String> send(String method, String path, String serviceKey)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (serviceKey != null) {
            request.header("X-Service-Key", serviceKey);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
