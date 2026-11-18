package com.lemma.external.service;

import com.lemma.external.TestClock;
import com.lemma.external.dto.AddressTransaction;
import com.lemma.external.dto.AddressTransaction.Direction;
import com.lemma.external.dto.AddressTransaction.Status;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** HistoryCache com relógio de teste, sem Thread.sleep. */
class HistoryCacheTest {

    private static final Duration VALIDITY = Duration.ofSeconds(12);

    private final TestClock clock = new TestClock(Instant.parse("2026-11-18T12:00:00Z"));
    private final HistoryCache cache = new HistoryCache(VALIDITY, HistoryCache.MAX_ADDRESSES, clock);

    private static List<AddressTransaction> list(int n) {
        return List.of(new AddressTransaction(String.format("0x%064x", n), Direction.RECEIVED,
                "0x6bdB5129e34773c822044a2078e6ba1576A258E6", "0.0005", "2026-11-16T04:12:00Z", Status.CONFIRMED));
    }

    private static String key(int n) {
        return String.format("0x%040x", n);
    }

    @Test
    void entryIsValidUntilTheExactInstantOfTheValidity() {
        cache.put(key(1), list(1));

        assertEquals(Optional.of(list(1)), cache.get(key(1)));
        clock.advance(VALIDITY.minusMillis(1));
        assertEquals(Optional.of(list(1)), cache.get(key(1)));
        clock.advance(Duration.ofMillis(1));
        assertEquals(Optional.empty(), cache.get(key(1)));
        assertEquals(0, cache.size());   // a vencida sai no acesso
    }

    @Test
    void emptyListIsCachedAndPutRenews() {
        cache.put(key(1), List.of());
        clock.advance(Duration.ofSeconds(10));
        cache.put(key(1), list(2));
        clock.advance(Duration.ofSeconds(10));

        assertEquals(Optional.of(list(2)), cache.get(key(1)));
        assertEquals(Optional.empty(), cache.get(key(2)));
    }

    /** Cheio, sem vencidos: sai o menos usado (ler conta como uso). */
    @Test
    void fullCacheDropsTheLeastRecentlyUsed() {
        for (int n = 0; n < HistoryCache.MAX_ADDRESSES; n++) {
            cache.put(key(n), list(n));
        }
        cache.get(key(0));   // o 0 passa a ser o mais recente; o 1 vira o menos usado

        cache.put(key(100), list(100));

        assertEquals(HistoryCache.MAX_ADDRESSES, cache.size());
        assertEquals(Optional.empty(), cache.get(key(1)));
        assertEquals(Optional.of(list(0)), cache.get(key(0)));
        assertEquals(Optional.of(list(100)), cache.get(key(100)));

        cache.put(key(2), list(22));   // renovar uma chave existente não tira ninguém
        assertEquals(HistoryCache.MAX_ADDRESSES, cache.size());
    }

    /** Cheio, com vencidos: saem todos os vencidos antes de tirar um válido. */
    @Test
    void fullCacheDropsExpiredEntriesFirst() {
        for (int n = 0; n < 50; n++) {
            cache.put(key(n), list(n));
        }
        clock.advance(Duration.ofSeconds(6));
        for (int n = 50; n < HistoryCache.MAX_ADDRESSES; n++) {
            cache.put(key(n), list(n));
        }
        clock.advance(Duration.ofSeconds(6));   // os 50 primeiros venceram agora

        cache.put(key(100), list(100));

        assertEquals(51, cache.size());
        for (int n = 50; n <= 100; n++) {
            assertEquals(Optional.of(list(n)), cache.get(key(n)), "chave " + n);
        }
    }

    @Test
    void manyThreadsAtOnceKeepTheLimit() throws Exception {
        ExecutorService threads = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> tasks = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                int offset = t * 1_000;
                tasks.add(threads.submit(() -> {
                    for (int n = 0; n < 1_000; n++) {
                        cache.put(key(offset + n % 150), list(n));
                        cache.get(key(offset + (n * 7) % 150));
                    }
                }));
            }
            for (Future<?> task : tasks) {
                task.get();
            }
        } finally {
            threads.shutdownNow();
        }
        assertTrue(cache.size() <= HistoryCache.MAX_ADDRESSES, String.valueOf(cache.size()));
        assertEquals(HistoryCache.MAX_ADDRESSES, cache.size());
    }

    @Test
    void rejectsInvalidSettings() {
        assertThrows(IllegalArgumentException.class, () -> new HistoryCache(Duration.ZERO, 100, clock));
        assertThrows(IllegalArgumentException.class, () -> new HistoryCache(Duration.ofSeconds(-1), 100, clock));
        assertThrows(IllegalArgumentException.class, () -> new HistoryCache(VALIDITY, 0, clock));
        assertThrows(NullPointerException.class, () -> new HistoryCache(VALIDITY, 100, null));
    }
}
