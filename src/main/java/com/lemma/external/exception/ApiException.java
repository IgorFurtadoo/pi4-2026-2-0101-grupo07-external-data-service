package com.lemma.external.exception;

/**
 * Erro previsto nos contratos: status HTTP + código em UPPER_SNAKE_CASE.
 * O handler central (controller/ErrorHandler) converte em { "code", "message" }.
 * A mensagem vai para quem chamou: nunca coloque segredo nela.
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /** 401 SERVICE_KEY_INVALID: X-Service-Key ausente ou incorreta (seção 5 do desenho). */
    public static ApiException serviceKeyInvalid() {
        return new ApiException(401, "SERVICE_KEY_INVALID", "Chave de serviço ausente ou incorreta.");
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
