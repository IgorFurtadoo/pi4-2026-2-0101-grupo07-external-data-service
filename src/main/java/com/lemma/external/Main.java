package com.lemma.external;

import com.lemma.external.config.AppConfig;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ponto de entrada do External Data Service.
 *
 * Lê a configuração e sobe o Javalin. Se faltar variável, um segredo estiver vazio
 * ou com o valor de exemplo, ou algum valor for inválido, encerra com código 1 e uma
 * mensagem clara, antes de abrir a porta. Sem injeção de dependência.
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        AppConfig config;
        try {
            config = AppConfig.fromEnv(System.getenv());
        } catch (IllegalStateException e) {
            log.error("Configuração inválida: {}", e.getMessage());
            System.exit(1);
            return;
        }

        Javalin app = Javalin.create(javalin -> {
        }).start(config.serverPort());

        // Desligamento limpo: o docker stop manda SIGTERM, a JVM roda este gancho
        // e o Javalin termina as requisições em andamento.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Desligando o External Data Service");
            app.stop();
        }));

        log.info("External Data Service no ar na porta {} com {}", config.serverPort(), config);
    }
}
