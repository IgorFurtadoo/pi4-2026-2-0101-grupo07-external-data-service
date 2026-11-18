package com.lemma.external.security;

import com.lemma.external.LogCapture;
import com.lemma.external.TestApp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static com.lemma.external.TestApp.SERVICE_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ServiceKeyFilter por HTTP real, com a configuração do Main e uma rota que só
 * existe neste teste (o E1 ainda não tem rota de negócio).
 */
class ServiceKeyFilterTest {

    private static final String SERVICE_KEY_INVALID =
            "{\"code\":\"SERVICE_KEY_INVALID\",\"message\":\"Chave de serviço ausente ou incorreta.\"}";

    private final TestApp app = new TestApp();
    /** Quantas vezes o handler da rota de teste rodou: a recusa vem antes dele. */
    private int handlerCalls;

    @BeforeEach
    void startServer() {
        app.start(javalin -> javalin.routes.get("/test/protegida/{id}", ctx -> {
            handlerCalls++;
            ctx.result("ok " + ctx.pathParam("id"));
        }));
    }

    @AfterEach
    void stopServer() {
        app.stop();
    }

    /** 401 exato, handler não chamado, ERROR com a rota registrada e sem a chave. */
    private void assertRefused(String serviceKey) throws Exception {
        try (LogCapture logs = new LogCapture(ServiceKeyFilter.class)) {
            HttpResponse<String> response = app.get("/test/protegida/abc", serviceKey);

            assertEquals(401, response.statusCode(), serviceKey);
            assertEquals(SERVICE_KEY_INVALID, response.body(), serviceKey);
            assertEquals(0, handlerCalls, serviceKey);
            assertEquals("ERROR Chave de serviço ausente ou incorreta em GET /test/protegida/{id}",
                    logs.all(), serviceKey);
            assertFalse(logs.all().contains(SERVICE_KEY), serviceKey);
            if (serviceKey != null && !serviceKey.isEmpty()) {
                assertFalse(logs.all().contains(serviceKey), serviceKey);
            }
        }
    }

    @Test
    void missingKeyIs401() throws Exception {
        assertRefused(null);
        assertRefused("");
    }

    @Test
    void wrongKeyIs401() throws Exception {
        assertRefused("outra-chave-qualquer");
        assertRefused(SERVICE_KEY + "x");                                   // um caractere a mais
        assertRefused(SERVICE_KEY.substring(0, SERVICE_KEY.length() - 1));  // um a menos
        assertRefused("x" + SERVICE_KEY.substring(1));                      // primeiro diferente
        assertRefused(SERVICE_KEY.toUpperCase());
        assertRefused("Bearer " + SERVICE_KEY);
    }

    @Test
    void rightKeyReachesTheRouteWithoutLog() throws Exception {
        try (LogCapture logs = new LogCapture(ServiceKeyFilter.class)) {
            HttpResponse<String> response = app.get("/test/protegida/abc", SERVICE_KEY);

            assertEquals(200, response.statusCode());
            assertEquals("ok abc", response.body());
            assertEquals(1, handlerCalls);
            assertEquals(0, logs.lines().size());
        }
    }

    /**
     * O tempo da comparação não é medido (seria um teste instável); o código usa
     * MessageDigest.isEqual. Aqui ficam os casos em que uma comparação ingênua
     * (prefixo, tamanho) costuma errar.
     */
    @Test
    void isValidAcceptsOnlyTheExactKey() {
        ServiceKeyFilter filter = new ServiceKeyFilter(SERVICE_KEY);

        assertTrue(filter.isValid(SERVICE_KEY));
        assertFalse(filter.isValid(null));
        assertFalse(filter.isValid(""));
        assertFalse(filter.isValid(SERVICE_KEY.substring(0, 5)));
        assertFalse(filter.isValid(SERVICE_KEY + SERVICE_KEY));
        assertFalse(filter.isValid(SERVICE_KEY + "\u0000"));
        assertFalse(new ServiceKeyFilter("").isValid(""));
    }
}
