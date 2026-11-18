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

    /** 400 ADDRESS_INVALID: endereço fora de ^0x[0-9a-fA-F]{40}$ no histórico (seção 7 do desenho). */
    public static ApiException addressInvalid() {
        return new ApiException(400, "ADDRESS_INVALID", "Endereço Ethereum inválido.");
    }

    /** 404 ASSET_NOT_FOUND: assetId fora do formato ou desconhecido na CoinGecko (seção 6). */
    public static ApiException assetNotFound() {
        return new ApiException(404, "ASSET_NOT_FOUND", "Ativo não encontrado.");
    }

    /** 502 MARKET_DATA_UNAVAILABLE: qualquer falha do CT19 ou do CT20 (seção 6). */
    public static ApiException marketDataUnavailable() {
        return new ApiException(502, "MARKET_DATA_UNAVAILABLE", "Dados de mercado indisponíveis.");
    }

    /**
     * 502 HISTORY_UNAVAILABLE: qualquer falha do CT25 (Blockscout fora do ar, limite
     * excedido, tempo esgotado, resposta fora do formato; seção 7 do desenho).
     */
    public static ApiException historyUnavailable() {
        return new ApiException(502, "HISTORY_UNAVAILABLE", "Histórico de transações indisponível.");
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
