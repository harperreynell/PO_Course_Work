package ua.kpi.search;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.stream.Stream;

final class SearchEngine {
    record Hit(String doc_id, double score, String snippet) {}
    record Stats(int documents, long index_build_time_ms, int threads_used) {}
    record Build(String status, int documents_indexed, long time_ms) {}
    record Results(List<Hit> results, long time_ms) {}

    private record Snapshot(InvertedIndex index, String[] ids, int[] lengths, double average, Stats stats) {}
    private static final Comparator<Hit> BEST_FIRST =
            Comparator.comparingDouble(Hit::score).reversed().thenComparing(Hit::doc_id);
    private static final int LIMIT = 10;
    private final Path directory;
    private volatile Snapshot current = new Snapshot(new InvertedIndex(), new String[0], new int[0],
            0, new Stats(0, 0, 0));

    SearchEngine(Path directory) {
        this.directory = directory;
    }

    static long milliseconds(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    /** Serialize builders. Readers capture one complete generation using a single volatile load. */
    synchronized Build rebuild(int threads) throws IOException, InterruptedException {
        if (threads < 1 || threads > 64) {
            throw new IllegalArgumentException("threads must be between 1 and 64");
        }
        long start = System.nanoTime();
        List<Path> paths;
        if (!Files.isDirectory(directory)) {
            throw new IOException("corpus directory is missing");
        }
        try (Stream<Path> files = Files.walk(directory)) {
            paths = files.filter(p -> p.toString().endsWith(".txt"))
                    .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).sorted().toList();
        }
        String[] ids = new String[paths.size()];
        int[] lengths = new int[paths.size()];
        InvertedIndex index = new InvertedIndex();
        try (TaskPool pool = new TaskPool(threads)) {
            for (int i = 0; i < paths.size(); i++) {
                final int document = i;
                Path file = paths.get(i);
                ids[i] = directory.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
                pool.submit(() -> lengths[document] = Tokenizer.index(file, document, index));
            }
        }
        double average = Arrays.stream(lengths).average().orElse(0);
        long elapsed = milliseconds(start);
        current = new Snapshot(index, ids, lengths, average, new Stats(ids.length, elapsed, threads));
        return new Build("ok", ids.length, elapsed);
    }

    Stats stats() {
        return current.stats;
    }

    Results search(String query) {
        long start = System.nanoTime();
        Snapshot snapshot = current;
        List<String> terms = Tokenizer.terms(query);
        int documents = snapshot.ids.length;
        double[] scores = new double[documents];
        double[] strongest = new double[documents];
        String[] snippets = new String[documents];
        for (String term : terms) {
            snapshot.index.visit(term, (doc, frequency, df, snippet) -> {
                double idf = Math.log1p((documents - df + 0.5) / (df + 0.5));
                double norm = 1.2 * (0.25 + 0.75 * snapshot.lengths[doc] / snapshot.average);
                double weight = idf * frequency * 2.2 / (frequency + norm);
                scores[doc] += weight;
                if (weight > strongest[doc]) {
                    strongest[doc] = weight;
                    snippets[doc] = snippet;
                }
            });
        }
        PriorityQueue<Hit> best = new PriorityQueue<>(BEST_FIRST.reversed());
        for (int doc = 0; doc < documents; doc++) {
            if (scores[doc] == 0) {
                continue;
            }
            Hit hit = new Hit(snapshot.ids[doc], scores[doc], snippets[doc]);
            if (best.size() < LIMIT) {
                best.add(hit);
            } else if (BEST_FIRST.compare(hit, best.element()) < 0) {
                best.remove();
                best.add(hit);
            }
        }
        List<Hit> hits = new ArrayList<>(best);
        hits.sort(BEST_FIRST);
        return new Results(hits, milliseconds(start));
    }
}
