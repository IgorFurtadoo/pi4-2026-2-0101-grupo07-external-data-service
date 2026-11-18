package com.lemma.external;

import com.lemma.external.config.AppConfig;
import com.lemma.external.controller.ErrorHandler;
import com.lemma.external.security.ServiceKeyFilter;
import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ponto de entrada do External Data Service.
 *
 * Lê a configuração e sobe o Javalin. Se faltar variável, um segredo estiver vazio
 * ou com o valor de exemplo, ou algum valor for inválido, encerra com código 1 e uma
 * mensagem clara, antes de abrir a porta. As peças são montadas à mão aqui, sem
 * injeção de dependência.
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

        Javalin app = createApp(config.serviceKey()).start(config.serverPort());

        // Desligamento limpo: o docker stop manda SIGTERM, a JVM roda este gancho
        // e o Javalin termina as requisições em andamento.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Desligando o External Data Service");
            app.stop();
        }));

        log.info("External Data Service no ar na porta {} com {}", config.serverPort(), config);
    }

    /** Cria o Javalin com a configuração de {@link #configure}, sem abrir a porta. */
    public static Javalin createApp(String serviceKey) {
        return Javalin.create(javalin -> configure(javalin, serviceKey));
    }

    /**
     * Configura handler de erros, filtro e rotas. Usado pelo main e pelos testes
     * HTTP, que assim cobrem a mesma configuração (e podem acrescentar rotas
     * próprias depois).
     *
     * O Javalin roda as fases nesta ordem, qualquer que seja a ordem de registro:
     * before → beforeMatched (chave de serviço) → rota (ou 404/405) → after.
     */
    public static void configure(JavalinConfig javalin, String serviceKey) {
        // Rota existente com método errado responde 405, não 404.
        javalin.http.prefer405over404 = true;
        ErrorHandler.register(javalin);

        // Chave de serviço em toda rota: beforeMatched só roda quando a rota existe,
        // então 404 e 405 ficam com a resposta padrão do Javalin.
        ServiceKeyFilter serviceKeyFilter = new ServiceKeyFilter(serviceKey);
        javalin.routes.beforeMatched(serviceKeyFilter::handle);

        // As rotas entram nos passos E2 (GET /market-data/{assetId}) e E3 (histórico).
    }
}
