package ua.kpi.search;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Tokenizer {
    static final int MAX_WORD = 64;
    private static final int CHUNK = 64 * 1024;
    private static final int WINDOW = 128;
    private static final int EXCERPT = WINDOW + MAX_WORD + 32;
    private static final Pattern WORD = Pattern.compile("[a-zA-Z0-9]+");
    private static final Pattern SPACE = Pattern.compile("\\s+");

    static List<String> terms(String query) {
        List<String> terms = new ArrayList<>();
        Matcher matcher = WORD.matcher(query);
        while (matcher.find()) {
            if (matcher.end() - matcher.start() <= MAX_WORD) {
                String term = matcher.group().toLowerCase(Locale.ROOT);
                if (!terms.contains(term)) {
                    terms.add(term);
                }
            }
        }
        return terms;
    }

    private static final class Count {
        final String snippet;
        int frequency;

        Count(String snippet) {
            this.snippet = snippet;
        }
    }

    private final WordTable<Count> counts = new WordTable<>();
    private final byte[] token = new byte[MAX_WORD];
    private int length;
    private int hash;
    private int total;
    private long snippetStart = -1;
    private String snippet;

    /** Workers own their scanner; no document body survives in the published index. */
    static int index(Path path, int document, InvertedIndex index) throws IOException {
        Tokenizer scanner = new Tokenizer();
        try (FileChannel file = FileChannel.open(path)) {
            ByteBuffer buffer = ByteBuffer.allocate(CHUNK);
            byte[] bytes = buffer.array();
            long offset = 0;
            int size;
            while ((size = file.read(buffer.clear())) != -1) {
                for (int i = 0; i < size; i++) {
                    int c = bytes[i];
                    if (c >= 'A' && c <= 'Z') {
                        c += 'a' - 'A';
                    }
                    if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                        if (scanner.length < MAX_WORD) {
                            scanner.token[scanner.length++] = (byte) c;
                            scanner.hash = 31 * scanner.hash + c;
                        } else {
                            scanner.length = MAX_WORD + 1;
                        }
                    } else if (scanner.length != 0) {
                        scanner.finish(file, offset + i, offset, bytes, size);
                    }
                }
                offset += size;
            }
            scanner.finish(file, offset, offset, bytes, 0);
        }
        scanner.counts.forEach((word, count) -> index.add(word, document, count.frequency, count.snippet));
        return scanner.total;
    }

    private void finish(FileChannel file, long end, long chunkStart, byte[] bytes, int size)
            throws IOException {
        if (length > 0 && length <= MAX_WORD) {
            Count count = counts.get(token, length, hash);
            if (count == null) {
                long windowStart = ((end - length) / WINDOW) * WINDOW;
                if (windowStart != snippetStart) {
                    snippet = excerpt(file, windowStart, chunkStart, bytes, size);
                    snippetStart = windowStart;
                }
                count = new Count(snippet);
                counts.putNew(new String(token, 0, length, StandardCharsets.US_ASCII), count);
            }
            count.frequency++;
            total = Math.incrementExact(total);
        }
        length = 0;
        hash = 0;
    }

    private static String excerpt(FileChannel file, long start, long chunkStart, byte[] bytes, int size)
            throws IOException {
        String text;
        if (start >= chunkStart && start + EXCERPT <= chunkStart + size) {
            text = new String(bytes, (int) (start - chunkStart), EXCERPT, StandardCharsets.UTF_8);
        } else {
            ByteBuffer slice = ByteBuffer.allocate(EXCERPT);
            while (slice.hasRemaining() && file.read(slice, start + slice.position()) > 0) {
                // Positional reads preserve the scanner's sequential position.
            }
            text = new String(slice.array(), 0, slice.position(), StandardCharsets.UTF_8);
        }
        return SPACE.matcher(text).replaceAll(" ").strip();
    }
}
