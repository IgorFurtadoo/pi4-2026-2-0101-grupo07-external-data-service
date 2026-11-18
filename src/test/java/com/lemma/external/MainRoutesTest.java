package com.lemma.external;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static com.lemma.external.TestApp.SERVICE_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Rota inexistente e método errado ficam com a resposta padrão do Javalin
 * (seção 9 do desenho), com ou sem a chave de serviço: o filtro só roda em rota
 * existente.
 */
class MainRoutesTest {

    private final TestApp app = new TestApp();

    @BeforeEach
    void startServer() {
        app.start(javalin -> javalin.routes.get("/test/protegida", ctx -> ctx.result("ok")));
    }

    @AfterEach
    void stopServer() {
        app.stop();
    }

    @Test
    void unknownRouteIs404WithTheJavalinDefault() throws Exception {
        for (String serviceKey : new String[] {null, "chave-errada", SERVICE_KEY}) {
            HttpResponse<String> response = app.get("/nao-existe", serviceKey);

            assertEquals(404, response.statusCode(), serviceKey);
            assertFalse(response.body().contains("SERVICE_KEY_INVALID"), serviceKey);
            assertFalse(response.body().contains("\"code\""), serviceKey);
        }
    }

    @Test
    void wrongMethodOnExistingRouteIs405WithTheJavalinDefault() throws Exception {
        for (String serviceKey : new String[] {null, SERVICE_KEY}) {
            HttpResponse<String> response = app.send("POST", "/test/protegida", serviceKey);

            assertEquals(405, response.statusCode(), serviceKey);
            assertFalse(response.body().contains("SERVICE_KEY_INVALID"), serviceKey);
        }
    }
}
