package com.lemma.external.service;

import com.lemma.external.dto.AddressTransaction;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Cache em memória do histórico por endereço (seção 7 do desenho), para não
 * gastar o limite do Blockscout público (180 requisições por minuto).
 *
 * Cada entrada vale enquanto agora < gravada + validade: no instante exato da
 * validade, já venceu. Guarda no máximo {@code maxEntries} endereços; ao gravar um
 * novo com o cache cheio, tira primeiro os vencidos e, se ainda faltar espaço, o
 * menos usado (o LinkedHashMap fica em ordem de acesso).
 *
 * Quem chama decide a chave (o service usa o endereço em minúsculas) e só grava
 * respostas de sucesso. Seguro para várias requisições ao mesmo tempo: get e put
 * são synchronized. Duas requisições simultâneas ao mesmo endereço fora do cache
 * podem chamar o Blockscout duas vezes; a segunda gravação só renova a entrada.
 *
 * Em memória: reiniciar zera. O relógio é injetável para os testes. Sem log.
 */
public class HistoryCache {

    /** Teto de endereços guardados (seção 7 do desenho). */
    public static final int MAX_ADDRESSES = 100;

    private record Entry(List<AddressTransaction> transactions, Instant expiresAt) {
    }

    private final Duration validity;
    private final int maxEntries;
    private final Clock clock;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);

    public HistoryCache(Duration validity, int maxEntries, Clock clock) {
        if (validity == null || validity.isZero() || validity.isNegative()) {
            throw new IllegalArgumentException("validity deve ser positiva");
        }
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries deve ser ao menos 1");
        }
        this.validity = validity;
        this.maxEntries = maxEntries;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** A lista guardada para a chave, se ainda valer; a entrada vencida sai na hora. */
    public synchronized Optional<List<AddressTransaction>> get(String key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (!clock.instant().isBefore(entry.expiresAt())) {
            entries.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.transactions());
    }

    /** Grava (ou renova) a lista da chave, abrindo espaço se o cache estiver cheio. */
    public synchronized void put(String key, List<AddressTransaction> transactions) {
        Instant now = clock.instant();
        if (!entries.containsKey(key) && entries.size() >= maxEntries) {
            entries.values().removeIf(entry -> !now.isBefore(entry.expiresAt()));
            if (entries.size() >= maxEntries) {
                Iterator<Map.Entry<String, Entry>> leastRecentlyUsed = entries.entrySet().iterator();
                leastRecentlyUsed.next();
                leastRecentlyUsed.remove();
            }
        }
        entries.put(key, new Entry(List.copyOf(transactions), now.plus(validity)));
    }

    /** Quantos endereços estão guardados agora (vencidos incluídos, até o próximo acesso). */
    public synchronized int size() {
        return entries.size();
    }
}
