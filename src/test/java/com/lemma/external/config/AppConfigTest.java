package com.lemma.external.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppConfigTest {

    // Valores só de teste, nunca usados fora daqui.
    private static final String TEST_SERVICE_KEY = "test-service-key";
    private static final String TEST_COINGECKO_API_KEY = "test-coingecko-api-key";

    private static Map<String, String> validEnv() {
        Map<String, String> env = new HashMap<>();
        env.put("SERVER_PORT", "8082");
        env.put("SERVICE_KEY", TEST_SERVICE_KEY);
        env.put("COINGECKO_API_KEY", TEST_COINGECKO_API_KEY);
        env.put("COINGECKO_BASE_URL", "https://api.coingecko.com/api/v3");
        env.put("COINGECKO_TIMEOUT_SECONDS", "6");
        return env;
    }

    private static String failureMessage(Map<String, String> env) {
        return assertThrows(IllegalStateException.class, () -> AppConfig.fromEnv(env)).getMessage();
    }

    private static Map<String, String> envWith(String name, String value) {
        Map<String, String> env = validEnv();
        env.put(name, value);
        return env;
    }

    @Test
    void loadsValidConfiguration() {
        AppConfig config = AppConfig.fromEnv(validEnv());

        assertEquals(8082, config.serverPort());
        assertEquals(TEST_SERVICE_KEY, config.serviceKey());
        assertEquals(TEST_COINGECKO_API_KEY, config.coingeckoApiKey());
        assertEquals("https://api.coingecko.com/api/v3", config.coingeckoBaseUrl());
        assertEquals(6, config.coingeckoTimeoutSeconds());
    }

    @Test
    void failsWhenAnyVariableIsMissing() {
        for (String name : validEnv().keySet()) {
            Map<String, String> env = validEnv();
            env.remove(name);

            assertEquals("variável obrigatória ausente ou vazia: " + name, failureMessage(env), name);
        }
    }

    @Test
    void failsWhenSecretIsEmptyOrBlank() {
        for (String name : new String[] {"SERVICE_KEY", "COINGECKO_API_KEY"}) {
            assertEquals("variável obrigatória ausente ou vazia: " + name, failureMessage(envWith(name, "")), name);
            assertEquals("variável obrigatória ausente ou vazia: " + name, failureMessage(envWith(name, "   ")), name);
        }
    }

    @Test
    void failsWhenSecretHasTheExampleValue() {
        assertEquals("SERVICE_KEY está com o valor de exemplo do .env.example:"
                        + " use o mesmo valor configurado no Core API",
                failureMessage(envWith("SERVICE_KEY", AppConfig.EXAMPLE_SERVICE_KEY)));
        assertEquals("COINGECKO_API_KEY está com o valor de exemplo do .env.example:"
                        + " troque pela chave do plano Demo da CoinGecko",
                failureMessage(envWith("COINGECKO_API_KEY", AppConfig.EXAMPLE_COINGECKO_API_KEY)));
    }

    @Test
    void exampleValueWithSurroundingSpacesIsAlsoRefused() {
        String message = failureMessage(envWith("SERVICE_KEY", "  " + AppConfig.EXAMPLE_SERVICE_KEY + " "));
        assertTrue(message.contains("SERVICE_KEY está com o valor de exemplo"), message);
    }

    @Test
    void reportsAllProblemsAtOnce() {
        Map<String, String> env = validEnv();
        env.remove("SERVER_PORT");
        env.put("SERVICE_KEY", "");
        env.put("COINGECKO_API_KEY", AppConfig.EXAMPLE_COINGECKO_API_KEY);

        String message = failureMessage(env);
        assertTrue(message.contains("variável obrigatória ausente ou vazia: SERVER_PORT"), message);
        assertTrue(message.contains("variável obrigatória ausente ou vazia: SERVICE_KEY"), message);
        assertTrue(message.contains("COINGECKO_API_KEY está com o valor de exemplo"), message);
    }

    @Test
    void failsWhenPortIsInvalid() {
        for (String value : new String[] {"abc", "0", "-1", "65536"}) {
            assertTrue(failureMessage(envWith("SERVER_PORT", value)).startsWith("SERVER_PORT inválida"), value);
        }
        assertEquals(65535, AppConfig.fromEnv(envWith("SERVER_PORT", "65535")).serverPort());
    }

    @Test
    void failsWhenTimeoutIsNotAPositiveInteger() {
        for (String value : new String[] {"abc", "0", "-6", "1.5"}) {
            assertEquals("COINGECKO_TIMEOUT_SECONDS inválida: precisa ser um número inteiro maior que zero",
                    failureMessage(envWith("COINGECKO_TIMEOUT_SECONDS", value)), value);
        }
    }

    @Test
    void failsWhenBaseUrlIsInvalid() {
        for (String value : new String[] {
                "api.coingecko.com/api/v3",
                "ftp://api.coingecko.com/api/v3",
                "https://",
                "https://user:pass@api.coingecko.com/api/v3",
                "https://api.coingecko.com/api/v3?x=1",
                "https://api.coingecko.com/api/v3#x",
                "https://api coingecko.com"}) {
            assertTrue(failureMessage(envWith("COINGECKO_BASE_URL", value)).startsWith("COINGECKO_BASE_URL inválida"),
                    value);
        }
    }

    @Test
    void baseUrlAcceptsHttpAndDropsTrailingSlash() {
        assertEquals("http://127.0.0.1:9999/api/v3",
                AppConfig.fromEnv(envWith("COINGECKO_BASE_URL", "http://127.0.0.1:9999/api/v3/")).coingeckoBaseUrl());
    }

    @Test
    void failureMessageNeverContainsTheValues() {
        Map<String, String> env = validEnv();
        env.put("SERVER_PORT", "porta-secreta");
        env.put("COINGECKO_BASE_URL", "https://user:senha-secreta@api.coingecko.com");

        String message = failureMessage(env);
        assertFalse(message.contains("porta-secreta"), message);
        assertFalse(message.contains("senha-secreta"), message);
        assertFalse(message.contains(TEST_SERVICE_KEY), message);
        assertFalse(message.contains(TEST_COINGECKO_API_KEY), message);
    }

    /**
     * O .env.example do repositório traz todas as variáveis, e só os dois segredos
     * são recusados, por estarem com o valor de exemplo. Trava o arquivo e as
     * constantes da AppConfig juntos.
     */
    @Test
    void envExampleIsCompleteAndOnlyItsSecretsAreRefused() throws IOException {
        Map<String, String> env = new HashMap<>();
        for (String line : Files.readAllLines(Path.of(".env.example"))) {
            if (!line.isBlank() && !line.startsWith("#")) {
                env.put(line.substring(0, line.indexOf('=')), line.substring(line.indexOf('=') + 1));
            }
        }

        assertEquals(validEnv().keySet(), env.keySet());
        assertEquals(AppConfig.EXAMPLE_SERVICE_KEY, env.get("SERVICE_KEY"));
        assertEquals(AppConfig.EXAMPLE_COINGECKO_API_KEY, env.get("COINGECKO_API_KEY"));

        String[] problems = failureMessage(env).split("; ");
        assertEquals(2, problems.length);
        assertTrue(problems[0].startsWith("SERVICE_KEY está com o valor de exemplo"), problems[0]);
        assertTrue(problems[1].startsWith("COINGECKO_API_KEY está com o valor de exemplo"), problems[1]);

        env.put("SERVICE_KEY", TEST_SERVICE_KEY);
        env.put("COINGECKO_API_KEY", TEST_COINGECKO_API_KEY);
        assertEquals(8082, AppConfig.fromEnv(env).serverPort());
    }

    @Test
    void toStringDoesNotExposeSecrets() {
        String text = AppConfig.fromEnv(validEnv()).toString();

        assertFalse(text.contains(TEST_SERVICE_KEY), text);
        assertFalse(text.contains(TEST_COINGECKO_API_KEY), text);
        assertEquals("AppConfig[serverPort=8082, coingeckoBaseUrl=https://api.coingecko.com/api/v3,"
                + " coingeckoTimeoutSeconds=6]", text);
    }
}
