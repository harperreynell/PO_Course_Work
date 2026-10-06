package ua.kpi.search;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(30)
class IndexTest {
    @TempDir Path dir;

    @Test void collisionsAndGrowthRetainAllValues() {
        WordTable<Integer> words = new WordTable<>();
        assertEquals("an".hashCode(), "c0".hashCode());
        List<String> keys = new ArrayList<>(List.of("an", "c0"));
        for (int i = 0; i < 2000; i++) {
            keys.add("term" + i);
        }
        for (int i = 0; i < keys.size(); i++) {
            words.putNew(keys.get(i), i);
        }
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            assertEquals(i, words.get(key));
            assertEquals(i, words.get(key.getBytes(StandardCharsets.US_ASCII), key.length(), key.hashCode()));
        }
        assertNull(words.get("absent"));
    }

    @Test void concurrentReadersSeeCompletePostingEntries() throws Exception {
        InvertedIndex index = new InvertedIndex();
        CountDownLatch go = new CountDownLatch(8);
        try (TaskPool pool = new TaskPool(8)) {
            for (int worker = 0; worker < 8; worker++) {
                int base = worker * 150;
                pool.submit(() -> {
                    go.countDown();
                    assertTrue(go.await(5, TimeUnit.SECONDS));
                    for (int i = base; i < base + 150; i++) {
                        index.add("same", i, i + 1, "doc" + i);
                        index.visit("same", (doc, tf, df, text) -> {
                            assertEquals(doc + 1, tf);
                            assertEquals("doc" + doc, text);
                        });
                    }
                });
            }
        }
        boolean[] seen = new boolean[1200];
        AtomicInteger total = new AtomicInteger();
        index.visit("same", (doc, tf, df, text) -> {
            assertFalse(seen[doc]);
            seen[doc] = true;
            assertEquals(1200, df);
            total.incrementAndGet();
        });
        assertEquals(1200, total.get());
    }

    @Test void tokenBoundariesRankingRebuildAndFailureKeepConsistentSnapshots() throws Exception {
        Path corpus = Files.createDirectory(dir.resolve("corpus"));
        Files.writeString(corpus.resolve("a.txt"), " ".repeat(65534) + "OrBiT, orbit! " + "x".repeat(65));
        Files.writeString(corpus.resolve("b.txt"), "Orbit and more words.");
        Files.writeString(corpus.resolve("ignore.csv"), "orbit");
        SearchEngine engine = new SearchEngine(corpus);
        assertEquals(0, engine.stats().documents());
        assertEquals(2, engine.rebuild(4).documents_indexed());
        List<SearchEngine.Hit> hits = engine.search("ORBIT?! orbit").results();
        assertEquals(List.of("a.txt", "b.txt"), hits.stream().map(SearchEngine.Hit::doc_id).toList());
        assertTrue(hits.getFirst().snippet().contains("OrBiT"));
        assertTrue(hits.getFirst().score() > hits.getLast().score());
        assertTrue(engine.search("x".repeat(64)).results().isEmpty());
        assertTrue(engine.search("!!!").results().isEmpty());
        Files.delete(corpus.resolve("a.txt"));
        assertEquals(hits, engine.search("orbit").results());
        assertEquals(1, engine.rebuild(1).documents_indexed());
        assertEquals(List.of("b.txt"), engine.search("orbit").results().stream().map(SearchEngine.Hit::doc_id).toList());
        Files.move(corpus, dir.resolve("hidden"));
        assertThrows(java.io.IOException.class, () -> engine.rebuild(2));
        assertEquals(1, engine.stats().threads_used());
        assertEquals(1, engine.search("orbit").results().size());
    }

    @Test void topTenTiesAreStableAcrossThreadCounts() throws Exception {
        for (int i = 0; i < 30; i++) {
            Files.writeString(dir.resolve("doc-%02d.txt".formatted(i)), "one word");
        }
        SearchEngine engine = new SearchEngine(dir);
        engine.rebuild(1);
        var hits = engine.search("word").results();
        assertEquals(10, hits.size());
        assertEquals("doc-00.txt", hits.getFirst().doc_id());
        assertEquals("doc-09.txt", hits.getLast().doc_id());
        engine.rebuild(8);
        assertEquals(hits, engine.search("word").results());
    }
}
