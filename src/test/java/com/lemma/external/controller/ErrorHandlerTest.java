package com.lemma.external.controller;

import com.lemma.external.LogCapture;
import com.lemma.external.TestApp;
import com.lemma.external.exception.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static com.lemma.external.TestApp.SERVICE_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Handler central por HTTP real, com rotas de teste que lançam exceções. */
class ErrorHandlerTest {

    private final TestApp app = new TestApp();

    @BeforeEach
    void startServer() {
        app.start(javalin -> {
            javalin.routes.get("/test/api-exception", ctx -> {
                throw new ApiException(502, "MARKET_DATA_UNAVAILABLE", "Dados de mercado indisponíveis.");
            });
            javalin.routes.get("/test/inesperado", ctx -> {
                throw new IllegalStateException("detalhe-interno-que-nao-pode-vazar");
            });
            javalin.routes.get("/test/inesperado/{valor}", ctx -> {
                throw new IllegalStateException("falha");
            });
        });
    }

    @AfterEach
    void stopServer() {
        app.stop();
    }

    @Test
    void apiExceptionBecomesItsStatusAndCode() throws Exception {
        HttpResponse<String> response = app.get("/test/api-exception", SERVICE_KEY);

        assertEquals(502, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        assertEquals("{\"code\":\"MARKET_DATA_UNAVAILABLE\",\"message\":\"Dados de mercado indisponíveis.\"}",
                response.body());
    }

    @Test
    void unexpectedExceptionIs500WithoutDetail() throws Exception {
        try (LogCapture logs = new LogCapture(ErrorHandler.class)) {
            HttpResponse<String> response = app.get("/test/inesperado", SERVICE_KEY);

            assertEquals(500, response.statusCode());
            assertEquals("{\"code\":\"INTERNAL_ERROR\",\"message\":\"Erro interno.\"}", response.body());
            assertFalse(response.body().contains("detalhe-interno"));
            assertEquals("ERROR Erro inesperado em GET /test/inesperado", logs.all());
            assertFalse(logs.all().contains(SERVICE_KEY));
        }
    }

    /** O log do 500 traz a rota registrada, não o valor que o chamador pôs no caminho. */
    @Test
    void unexpectedExceptionLogsTheRouteNotThePath() throws Exception {
        try (LogCapture logs = new LogCapture(ErrorHandler.class)) {
            HttpResponse<String> response = app.get("/test/inesperado/0xbdd26d8f9b9876d59cd6ebe1f25a1286b8ecacd5",
                    SERVICE_KEY);

            assertEquals(500, response.statusCode());
            assertEquals("ERROR Erro inesperado em GET /test/inesperado/{valor}", logs.all());
        }
    }
}
