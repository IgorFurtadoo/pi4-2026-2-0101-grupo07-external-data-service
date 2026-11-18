package com.lemma.external;

import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.function.Consumer;

/**
 * Sobe o serviço numa porta livre de 127.0.0.1 com a mesma configuração do Main
 * (Main.configure) e uma chave de serviço de teste. As rotas que os testes usam
 * só existem aqui (o E1 não tem rota de negócio). Sem rede externa.
 */
public final class TestApp {

    /** Chave de serviço obviamente falsa, só para os testes. */
    public static final String SERVICE_KEY = "chave-de-servico-de-teste-falsa";

    private static final HttpClient http = HttpClient.newHttpClient();

    private Javalin app;

    /** Sobe com a configuração do Main e, depois dela, as rotas que só existem no teste. */
    public TestApp start(Consumer<JavalinConfig> testRoutes) {
        app = Javalin.create(javalin -> {
            Main.configure(javalin, SERVICE_KEY);
            testRoutes.accept(javalin);
        }).start("127.0.0.1", 0);
        return this;
    }

    public void stop() {
        if (app != null) {
            app.stop();
        }
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
