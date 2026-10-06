package ua.kpi.search;

import java.util.Arrays;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Each (term, document) is submitted once, after local frequency aggregation. */
final class InvertedIndex {
    interface Visitor {
        void accept(int document, int frequency, int documentFrequency, String snippet);
    }

    private static final class Postings {
        int[] documents = new int[4];
        int[] frequencies = new int[4];
        String[] snippets = new String[4];
        int size;

        void append(int document, int frequency, String snippet) {
            if (size == documents.length) {
                documents = Arrays.copyOf(documents, size * 2);
                frequencies = Arrays.copyOf(frequencies, size * 2);
                snippets = Arrays.copyOf(snippets, size * 2);
            }
            documents[size] = document;
            frequencies[size] = frequency;
            snippets[size] = snippet;
            size++;
        }
    }

    private static final class Shard {
        final WordTable<Postings> words = new WordTable<>();
        final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    }

    private final Shard[] shards = new Shard[32];

    InvertedIndex() {
        Arrays.setAll(shards, unused -> new Shard());
    }

    private Shard shard(String word) {
        return shards[(WordTable.spread(word.hashCode()) >>> 8) & (shards.length - 1)];
    }

    void add(String term, int document, int frequency, String snippet) {
        Shard shard = shard(term);
        shard.lock.writeLock().lock();
        try {
            Postings postings = shard.words.get(term);
            if (postings == null) {
                postings = new Postings();
                shard.words.putNew(term, postings);
            }
            postings.append(document, frequency, snippet);
        } finally {
            shard.lock.writeLock().unlock();
        }
    }

    void visit(String term, Visitor visitor) {
        Shard shard = shard(term);
        shard.lock.readLock().lock();
        try {
            Postings list = shard.words.get(term);
            if (list != null) {
                for (int i = 0; i < list.size; i++) {
                    visitor.accept(list.documents[i], list.frequencies[i], list.size, list.snippets[i]);
                }
            }
        } finally {
            shard.lock.readLock().unlock();
        }
    }
}
