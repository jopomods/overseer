package io.github.jopojp.overseer.core.http;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.time.Instant;

/** Shared Gson setup. Gson ships with Minecraft (ADR-0003 rationale applies). */
public final class Json {
    public static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .registerTypeAdapter(Instant.class, new InstantAdapter().nullSafe())
            .create();

    private Json() {}

    /** Parses a request body; malformed JSON becomes a 400. */
    public static <T> T parse(String body, Class<T> type) {
        try {
            T value = GSON.fromJson(body, type);
            if (value == null) throw HttpError.badRequest("Request body is empty.");
            return value;
        } catch (JsonParseException e) {
            throw HttpError.badRequest("Request body is not valid JSON.");
        }
    }

    private static final class InstantAdapter extends TypeAdapter<Instant> {
        @Override
        public void write(JsonWriter out, Instant value) throws IOException {
            out.value(value.toString());
        }

        @Override
        public Instant read(JsonReader in) throws IOException {
            return Instant.parse(in.nextString());
        }
    }
}
