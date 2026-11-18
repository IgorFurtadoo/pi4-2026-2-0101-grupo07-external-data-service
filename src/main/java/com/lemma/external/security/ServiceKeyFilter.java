package com.lemma.external.security;

import com.lemma.external.exception.ApiException;
import io.javalin.http.Context;
import io.javalin.router.Endpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Exige o cabeçalho X-Service-Key em todas as rotas (seção 5 do desenho): quem
 * chama este módulo é só o Core API, e toda rota é entre serviços.
 *
 * Registrado no Main com beforeMatched: roda antes do handler de qualquer rota
 * existente, antes de qualquer outra conferência, e só quando existe rota para o
 * caminho e o método. Assim 404 e 405 ficam com a resposta padrão do Javalin.
 *
 * Cabeçalho ausente, vazio ou diferente da SERVICE_KEY: 401 SERVICE_KEY_INVALID,
 * com ERROR no log (seção 6 do desenho). A chave (a esperada e a recebida) nunca
 * vai ao log.
 */
public class ServiceKeyFilter {

    private static final Logger log = LoggerFactory.getLogger(ServiceKeyFilter.class);

    static final String HEADER = "X-Service-Key";

    private final byte[] expectedKey;

    /** @param serviceKey a SERVICE_KEY da configuração */
    public ServiceKeyFilter(String serviceKey) {
        this.expectedKey = serviceKey.getBytes(StandardCharsets.UTF_8);
    }

    public void handle(Context ctx) {
        if (!isValid(ctx.header(HEADER))) {
            log.error("Chave de serviço ausente ou incorreta em {}", matchedRoute(ctx));
            throw ApiException.serviceKeyInvalid();
        }
    }

    /**
     * Comparação em tempo constante: MessageDigest.isEqual percorre sempre a chave
     * esperada inteira (1º argumento), sem parar no primeiro byte diferente, então
     * o tempo não revela quantos caracteres da chave enviada estão certos.
     * Cabeçalho ausente ou vazio é sempre recusado, qualquer que seja a chave esperada.
     */
    boolean isValid(String providedKey) {
        if (providedKey == null || providedKey.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(expectedKey, providedKey.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Método e caminho como foram registrados ("GET /market-data/{assetId}"), não o
     * caminho pedido: o log não repete o que o chamador mandou.
     */
    private static String matchedRoute(Context ctx) {
        Endpoint matched = ctx.endpoints().matchedHttpEndpoint();
        return matched == null ? ctx.method().name() : matched.method.name() + " " + matched.path;
    }
}
