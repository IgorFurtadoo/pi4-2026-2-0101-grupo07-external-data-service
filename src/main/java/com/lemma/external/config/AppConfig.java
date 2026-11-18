package com.lemma.external.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Configuração do serviço, lida das variáveis de ambiente (seção 3.2 do desenho).
 *
 * Todas as variáveis são obrigatórias. Se faltar alguma, se um segredo estiver
 * vazio ou com o valor de exemplo do .env.example, ou se um valor for inválido,
 * {@link #fromEnv(Map)} lança {@link IllegalStateException} listando todos os
 * problemas de uma vez. A mensagem cita só o nome da variável, nunca o valor,
 * para não vazar segredo em log.
 */
public record AppConfig(
        int serverPort,
        String serviceKey,
        String coingeckoApiKey,
        String coingeckoBaseUrl,
        int coingeckoTimeoutSeconds) {

    // Valores de exemplo do .env.example. São recusados para o serviço nunca
    // subir com um segredo que está publicado no repositório.
    static final String EXAMPLE_SERVICE_KEY = "TROCAR_mesmo_valor_do_core_api";
    static final String EXAMPLE_COINGECKO_API_KEY = "TROCAR_chave_do_plano_demo_da_coingecko";

    private static final int MAX_PORT = 65535;

    public static AppConfig fromEnv(Map<String, String> env) {
        Reader reader = new Reader(env);

        int serverPort = reader.port("SERVER_PORT");
        String serviceKey = reader.secret("SERVICE_KEY", EXAMPLE_SERVICE_KEY,
                "use o mesmo valor configurado no Core API");
        String coingeckoApiKey = reader.secret("COINGECKO_API_KEY", EXAMPLE_COINGECKO_API_KEY,
                "troque pela chave do plano Demo da CoinGecko");
        String coingeckoBaseUrl = reader.httpUrl("COINGECKO_BASE_URL");
        int coingeckoTimeoutSeconds = reader.positiveInt("COINGECKO_TIMEOUT_SECONDS");

        if (!reader.problems.isEmpty()) {
            throw new IllegalStateException(String.join("; ", reader.problems));
        }

        return new AppConfig(serverPort, serviceKey, coingeckoApiKey, coingeckoBaseUrl, coingeckoTimeoutSeconds);
    }

    /** Não imprime os segredos (chave de serviço e chave da CoinGecko). */
    @Override
    public String toString() {
        return "AppConfig[serverPort=" + serverPort
                + ", coingeckoBaseUrl=" + coingeckoBaseUrl
                + ", coingeckoTimeoutSeconds=" + coingeckoTimeoutSeconds + "]";
    }

    /** Lê cada variável e acumula os problemas encontrados, sem parar no primeiro. */
    private static final class Reader {
        private final Map<String, String> env;
        private final List<String> problems = new ArrayList<>();

        Reader(Map<String, String> env) {
            this.env = env;
        }

        String text(String name) {
            String value = env.get(name);
            if (value == null || value.isBlank()) {
                problems.add("variável obrigatória ausente ou vazia: " + name);
                return null;
            }
            return value.trim();
        }

        int positiveInt(String name) {
            String value = text(name);
            if (value == null) {
                return 0;
            }
            try {
                int number = Integer.parseInt(value);
                if (number > 0) {
                    return number;
                }
            } catch (NumberFormatException e) {
                // cai na mensagem abaixo
            }
            problems.add(name + " inválida: precisa ser um número inteiro maior que zero");
            return 0;
        }

        int port(String name) {
            int number = positiveInt(name);
            if (number > MAX_PORT) {
                problems.add(name + " inválida: precisa estar entre 1 e " + MAX_PORT);
                return 0;
            }
            return number;
        }

        /** Segredo obrigatório que não pode ficar com o valor de exemplo do .env.example. */
        String secret(String name, String exampleValue, String hint) {
            String value = text(name);
            if (value == null) {
                return null;
            }
            if (value.equals(exampleValue)) {
                problems.add(name + " está com o valor de exemplo do .env.example: " + hint);
                return null;
            }
            return value;
        }

        /** Base de API externa: http ou https, com host, sem usuário, query nem fragmento. */
        String httpUrl(String name) {
            String value = text(name);
            if (value == null) {
                return null;
            }
            if (!isHttpUrl(value)) {
                problems.add(name + " inválida: precisa ser uma URL http:// ou https:// com host,"
                        + " sem usuário, query nem fragmento");
                return null;
            }
            // Sem barra final, para o cliente montar base + "/caminho".
            return value.replaceAll("/+$", "");
        }

        private static boolean isHttpUrl(String value) {
            URI uri;
            try {
                uri = new URI(value);
            } catch (URISyntaxException e) {
                return false;
            }
            boolean http = "http".equals(uri.getScheme()) || "https".equals(uri.getScheme());
            return http && uri.getHost() != null && uri.getRawUserInfo() == null
                    && uri.getRawQuery() == null && uri.getRawFragment() == null;
        }
    }
}
