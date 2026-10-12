package ua.kpi.search;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;

final class Wire {
    static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    static final int MAX_BODY = 8192;

    static JsonObject object(String raw) {
        JsonObject value = JSON.fromJson(raw, JsonObject.class);
        if (value == null) {
            throw new IllegalArgumentException("expected a JSON object");
        }
        return value;
    }

    static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        return value.getAsString();
    }

    static String query(JsonObject body) {
        String query = string(body, "query");
        if (query.isBlank() || query.length() > 512) {
            throw new IllegalArgumentException("query must contain 1 to 512 characters");
        }
        return query;
    }

    static int threads(JsonObject body) {
        JsonElement value = body.get("threads");
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("threads must be an integer");
        }
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException("threads must be an integer");
        }
    }

    private Wire() {}
}
