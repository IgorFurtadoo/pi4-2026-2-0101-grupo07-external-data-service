package com.lemma.external.controller;

import com.lemma.external.dto.ErrorResponse;
import com.lemma.external.exception.ApiException;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.javalin.router.Endpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler central de erros, registrado no Main.
 *
 * ApiException vira o status e o código dela; qualquer outra exceção vira
 * 500 INTERNAL_ERROR sem expor detalhe interno ao chamador. Rota inexistente (404)
 * e método errado (405) ficam com a resposta padrão do Javalin, que tem handler
 * próprio para esses casos.
 *
 * O log do 500 traz a rota como foi registrada ("GET /addresses/{ethereumAddress}/transactions"),
 * não o caminho pedido: o endereço ou o assetId do chamador não vão ao log.
 */
public final class ErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);

    private ErrorHandler() {
    }

    /** Registra os handlers na configuração do Javalin (usado pelo Main e pelos testes). */
    public static void register(JavalinConfig config) {
        config.routes.exception(ApiException.class, ErrorHandler::handleApiException);
        config.routes.exception(Exception.class, ErrorHandler::handleUnexpected);
    }

    static void handleApiException(ApiException e, Context ctx) {
        ctx.status(e.getStatus()).json(new ErrorResponse(e.getCode(), e.getMessage()));
    }

    static void handleUnexpected(Exception e, Context ctx) {
        // Loga só a rota registrada e a exceção; nunca cabeçalhos (a chave de serviço vem num deles).
        log.error("Erro inesperado em {}", matchedRoute(ctx), e);
        ctx.status(500).json(new ErrorResponse("INTERNAL_ERROR", "Erro interno."));
    }

    /** Método e caminho como foram registrados; sem rota casada, só o método. */
    private static String matchedRoute(Context ctx) {
        Endpoint matched = ctx.endpoints().matchedHttpEndpoint();
        return matched == null ? ctx.method().name() : matched.method.name() + " " + matched.path;
    }
}
