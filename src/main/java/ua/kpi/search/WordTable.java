package ua.kpi.search;

import java.util.function.BiConsumer;

/** No deletion: the first empty slot terminates a probe. Its owner provides synchronization. */
final class WordTable<V> {
    private String[] keys = new String[8];
    private Object[] values = new Object[8];
    private int size;

    static int spread(int hash) {
        return hash ^ (hash >>> 16);
    }

    private int slot(String key) {
        int mask = keys.length - 1;
        int at = spread(key.hashCode()) & mask;
        while (keys[at] != null && !keys[at].equals(key)) {
            at = (at + 1) & mask;
        }
        return at;
    }

    @SuppressWarnings("unchecked")
    private V value(int at) {
        return (V) values[at];
    }

    V get(String key) {
        return value(slot(key));
    }

    /** Same polynomial hash as String.hashCode(), without allocating a String per occurrence. */
    V get(byte[] token, int length, int hash) {
        int mask = keys.length - 1;
        int at = spread(hash) & mask;
        while (keys[at] != null) {
            String key = keys[at];
            if (key.hashCode() == hash && key.length() == length) {
                int i = 0;
                while (i < length && key.charAt(i) == token[i]) {
                    i++;
                }
                if (i == length) {
                    return value(at);
                }
            }
            at = (at + 1) & mask;
        }
        return null;
    }

    void putNew(String key, V item) {
        if (size + 1 > keys.length * 2L / 3) {
            String[] oldKeys = keys;
            Object[] oldValues = values;
            keys = new String[oldKeys.length * 2];
            values = new Object[keys.length];
            for (int i = 0; i < oldKeys.length; i++) {
                if (oldKeys[i] != null) {
                    int at = slot(oldKeys[i]);
                    keys[at] = oldKeys[i];
                    values[at] = oldValues[i];
                }
            }
        }
        int at = slot(key);
        if (keys[at] != null) {
            throw new IllegalStateException("duplicate key");
        }
        keys[at] = key;
        values[at] = item;
        size++;
    }

    void forEach(BiConsumer<String, V> visitor) {
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] != null) {
                visitor.accept(keys[i], value(i));
            }
        }
    }
}
