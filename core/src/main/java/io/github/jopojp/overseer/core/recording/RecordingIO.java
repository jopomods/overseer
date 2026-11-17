package io.github.jopojp.overseer.core.recording;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;

/**
 * Reads and writes recordings as JSON Lines: line 1 is the {@link Recording.Header},
 * every following line one {@link Recording.Frame}. Line-based so diffs stay readable
 * and files can be streamed.
 */
public final class RecordingIO {
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .registerTypeAdapter(Instant.class, new TypeAdapter<Instant>() {
                @Override
                public void write(JsonWriter out, Instant value) throws IOException {
                    out.value(value.toString());
                }

                @Override
                public Instant read(JsonReader in) throws IOException {
                    return Instant.parse(in.nextString());
                }
            }.nullSafe())
            .create();

    private RecordingIO() {}

    public static void write(Recording recording, Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            write(recording, out);
        }
    }

    public static void write(Recording recording, Writer out) throws IOException {
        out.write(GSON.toJson(recording.header()));
        out.write('\n');
        for (Recording.Frame frame : recording.snapshots()) {
            out.write(GSON.toJson(frame));
            out.write('\n');
        }
    }

    public static Recording read(Path file) throws IOException {
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return read(in);
        }
    }

    public static Recording read(Reader reader) throws IOException {
        var in = reader instanceof BufferedReader b ? b : new BufferedReader(reader);
        String first = in.readLine();
        if (first == null) throw new IOException("empty recording");
        Recording.Header header = GSON.fromJson(first, Recording.Header.class);
        if (header == null || !Recording.FORMAT.equals(header.format())) {
            throw new IOException("not an Overseer recording");
        }
        if (header.version() > Recording.VERSION) {
            throw new IOException("recording version " + header.version() + " is newer than supported ("
                    + Recording.VERSION + ")");
        }
        var frames = new ArrayList<Recording.Frame>();
        String line;
        while ((line = in.readLine()) != null) {
            if (!line.isBlank()) frames.add(GSON.fromJson(line, Recording.Frame.class));
        }
        return new Recording(header, frames);
    }
}
