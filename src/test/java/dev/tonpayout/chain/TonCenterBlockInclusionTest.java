package dev.tonpayout.chain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import dev.tonpayout.chain.TonCenterBlockInclusion.PageLoader;
import dev.tonpayout.chain.TonCenterBlockInclusion.WalletHistory;

class TonCenterBlockInclusionTest {
    private static final String NEEDLE = "ab".repeat(32);
    private static final long NOW = 1_700_000_000L;

    @Test
    void hashPastTheFirstPageUsesLtAndHash() throws Exception {
        WalletHistory history = history(100, 200, NOW, null);
        List<String> calls = new ArrayList<>();
        PageLoader loader = (lt, hash) -> {
            calls.add(lt + "|" + hash);
            List<Object> older = new ArrayList<>();
            older.add(history.get(history.size() - 1));
            older.add(tx("100", "tx-100", NEEDLE, NOW));
            return older;
        };
        assertTrue(TonCenterBlockInclusion.find(history, List.of(NEEDLE), loader, NOW - 1000, 5));
        assertEquals(List.of("101|tx-101"), calls);
        assertFalse(history.exhausted);
    }

    @Test
    void hashOnTheFirstPageDoesNotAskForOlderTransactions() throws Exception {
        WalletHistory history = history(100, 200, NOW, NEEDLE);
        AtomicInteger calls = new AtomicInteger();
        assertTrue(TonCenterBlockInclusion.find(history, List.of(NEEDLE), (lt, hash) -> {
            calls.incrementAndGet();
            return List.of();
        }, NOW - 1000, 5));
        assertEquals(0, calls.get());
    }

    @Test
    void shortPageIsTheEndOfHistory() throws Exception {
        WalletHistory history = TonCenterBlockInclusion.historyOf(List.of(tx("5", "tx-5", "11".repeat(32), NOW)));
        assertTrue(history.exhausted);
        AtomicInteger calls = new AtomicInteger();
        assertFalse(TonCenterBlockInclusion.find(history, List.of(NEEDLE), (lt, hash) -> {
            calls.incrementAndGet();
            return List.of();
        }, 0, 5));
        assertEquals(0, calls.get());
    }

    @Test
    void repeatedCursorDoesNotWalkForever() throws Exception {
        WalletHistory history = history(100, 200, NOW, null);
        AtomicInteger calls = new AtomicInteger();
        assertFalse(TonCenterBlockInclusion.find(history, List.of(NEEDLE), (lt, hash) -> {
            calls.incrementAndGet();
            return List.of(tx(lt, hash, "11".repeat(32), NOW));
        }, 0, 5));
        assertEquals(1, calls.get());
        assertTrue(history.exhausted);
    }

    @Test
    void pagesOlderThanTheMessageWindowAreNotRequested() throws Exception {
        WalletHistory history = history(100, 200, 50, null);
        AtomicInteger calls = new AtomicInteger();
        assertFalse(TonCenterBlockInclusion.find(history, List.of(NEEDLE), (lt, hash) -> {
            calls.incrementAndGet();
            return List.of();
        }, 1_000, 5));
        assertEquals(0, calls.get());
        assertTrue(history.exhausted);
    }

    @Test
    void transactionIdHashIsOnlyTheCursor() throws Exception {
        WalletHistory history = history(100, 200, NOW, null);
        history.set(0, tx("200", NEEDLE, "11".repeat(32), NOW));
        AtomicInteger calls = new AtomicInteger();
        assertFalse(TonCenterBlockInclusion.find(history, List.of(NEEDLE), (lt, hash) -> {
            calls.incrementAndGet();
            return List.of();
        }, 0, 5));
        assertEquals(1, calls.get());
    }

    @Test
    void loadedPageIsReusedForTheNextBatch() throws Exception {
        WalletHistory history = history(100, 200, NOW, null);
        List<Object> older = new ArrayList<>();
        older.add(tx("101", "tx-101", "11".repeat(32), NOW));
        older.add(tx("100", "tx-100", NEEDLE, NOW));
        AtomicInteger calls = new AtomicInteger();
        PageLoader loader = (lt, hash) -> {
            calls.incrementAndGet();
            return older;
        };
        assertTrue(TonCenterBlockInclusion.find(history, List.of(NEEDLE), loader, 0, 5));
        assertTrue(TonCenterBlockInclusion.find(history, List.of(NEEDLE, "11".repeat(32)), loader, 0, 5));
        assertEquals(1, calls.get());
    }

    @Test
    void cursorKeepsUint64LogicalTime() {
        Map<String, Object> id = new LinkedHashMap<>();
        id.put("lt", new BigInteger("18446744073709551615"));
        id.put("hash", "ab+/=");
        Map<String, Object> tx = new LinkedHashMap<>();
        tx.put("transaction_id", id);
        assertEquals("18446744073709551615", TonCenterBlockInclusion.logicalTime(tx));
        assertEquals("ab+/=", TonCenterBlockInclusion.transactionHash(tx));
    }

    @Test
    void olderPageQueryCarriesLtAndHash() {
        String address = "0:" + "cd".repeat(32);
        String first = TonCenterBlockInclusion.transactionsUrl("https://toncenter.com/api/v2", address, null, null);
        assertTrue(first.startsWith("https://toncenter.com/api/v2/getTransactions?"));
        assertTrue(first.contains("limit=100"));
        assertFalse(first.contains("lt="));
        String older = TonCenterBlockInclusion.transactionsUrl(
                "https://toncenter.com/api/v2/", address, "18446744073709551615", "ab+/=");
        assertTrue(older.contains("address=0%3A" + "cd".repeat(32)));
        assertTrue(older.contains("lt=18446744073709551615"));
        assertTrue(older.contains("hash=ab%2B%2F%3D"));
    }

    private static WalletHistory history(int count, long newestLt, long utime, String inHashOnLast) {
        WalletHistory history = new WalletHistory();
        for (int i = 0; i < count; i++) {
            long lt = newestLt - i;
            String inHash = i == count - 1 && inHashOnLast != null ? inHashOnLast : "11".repeat(32);
            history.add(tx(Long.toString(lt), "tx-" + lt, inHash, utime));
        }
        return history;
    }

    private static Map<String, Object> tx(String lt, String txHash, String inHash, long utime) {
        Map<String, Object> id = new LinkedHashMap<>();
        id.put("lt", lt);
        id.put("hash", txHash);
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("hash", inHash);
        Map<String, Object> tx = new LinkedHashMap<>();
        tx.put("transaction_id", id);
        tx.put("in_msg", in);
        tx.put("utime", utime);
        return tx;
    }
}
