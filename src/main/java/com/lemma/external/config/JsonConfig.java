package com.lemma.external.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.javalin.json.JavalinJackson;

/**
 * Configuração do JSON (seção 6 do desenho): preços exatos na entrada e na saída.
 *
 * Saída (Javalin): BigDecimal sai sempre em notação plana (toPlainString), como no
 * Core API: 1.2E-7 vira 0.00000012, nunca notação científica. O Core API confere
 * o formato do número que recebe.
 *
 * Entrada (corpos da CoinGecko): decimais lidos como BigDecimal exato, sem passar
 * por double, e sem tirar os zeros à direita (3200.450 continua 3200.450).
 */
public final class JsonConfig {

    private static final ObjectMapper READER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    private JsonConfig() {
    }

    /** Mapper das respostas do Javalin, registrado no Main. */
    public static JavalinJackson jsonMapper() {
        return new JavalinJackson().updateMapper(mapper -> mapper.enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN));
    }

    /** Leitor dos corpos da CoinGecko (só leitura; thread-safe depois de configurado). */
    public static ObjectMapper reader() {
        return READER;
    }
}
