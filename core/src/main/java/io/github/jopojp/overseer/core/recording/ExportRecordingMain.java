package io.github.jopojp.overseer.core.recording;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.sqlite.SQLiteConfig;

/**
 * {@code ./gradlew :core:exportRecording --args="<overseer.db> <out.jsonl> [intervalMillis] [--providers=ae2,server] [--last=30] [--raw]"}
 *
 * <p>Exports a database into a recording. Anonymized by default; {@code --raw} keeps real
 * players and coordinates and must never be committed. {@code --providers} keeps only those
 * modules, {@code --last} only the last N minutes of server uptime: a full history is far too
 * big to commit.
 */
public final class ExportRecordingMain {
    private ExportRecordingMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: <overseer.db> <out.jsonl> [intervalMillis=5000] [--providers=a,b] [--last=minutes] [--raw]");
            System.exit(2);
        }
        Path db = Path.of(args[0]);
        Path out = Path.of(args[1]);
        long interval = args.length > 2 && !args[2].startsWith("--") ? Long.parseLong(args[2]) : 5_000;
        List<String> options = Arrays.asList(args);
        boolean raw = options.contains("--raw");
        Set<String> providers = option(options, "--providers=")
                .map(v -> Set.of(v.split(",")))
                .orElse(Set.of());
        long lastMillis = option(options, "--last=").map(v -> Long.parseLong(v) * 60_000).orElse(0L);
        if (!Files.exists(db)) throw new IllegalArgumentException("no such database: " + db);

        var config = new SQLiteConfig();
        config.setReadOnly(true);
        Recording recording;
        try (var connection = config.createConnection("jdbc:sqlite:" + db.toAbsolutePath())) {
            recording = RecordingExporter.export(connection, interval,
                    "Exported from " + db.getFileName() + " every " + interval + " ms");
        }
        recording = select(recording, providers, lastMillis);
        if (!raw) recording = Anonymizer.anonymize(recording);
        RecordingIO.write(recording, out);
        System.out.printf("Wrote %d frames (%s) to %s%n", recording.snapshots().size(),
                raw ? "RAW - do not commit" : "anonymized", out.toAbsolutePath());
    }

    /**
     * Keeps only the given providers (all if empty) and the frames of the last {@code lastMillis}
     * of uptime (all if 0).
     */
    static Recording select(Recording recording, Set<String> providers, long lastMillis) {
        long end = recording.snapshots().stream().mapToLong(Recording.Frame::uptimeMillis).max().orElse(0);
        long from = lastMillis > 0 ? end - lastMillis : Long.MIN_VALUE;
        List<Recording.Frame> frames = recording.snapshots().stream()
                .filter(f -> providers.isEmpty() || providers.contains(f.provider()))
                .filter(f -> f.uptimeMillis() >= from)
                .toList();
        return new Recording(recording.header(), frames);
    }

    private static java.util.Optional<String> option(List<String> args, String prefix) {
        return args.stream().filter(a -> a.startsWith(prefix)).map(a -> a.substring(prefix.length())).findFirst();
    }
}
