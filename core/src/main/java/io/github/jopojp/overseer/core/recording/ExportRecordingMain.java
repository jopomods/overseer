package io.github.jopojp.overseer.core.recording;

import java.nio.file.Files;
import java.nio.file.Path;
import org.sqlite.SQLiteConfig;

/**
 * {@code ./gradlew :core:exportRecording --args="<overseer.db> <out.jsonl> [intervalMillis] [--raw]"}
 *
 * <p>Exports a database into a recording. Anonymized by default; {@code --raw} keeps real
 * players and coordinates and must never be committed.
 */
public final class ExportRecordingMain {
    private ExportRecordingMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: <overseer.db> <out.jsonl> [intervalMillis=5000] [--raw]");
            System.exit(2);
        }
        Path db = Path.of(args[0]);
        Path out = Path.of(args[1]);
        long interval = args.length > 2 && !args[2].startsWith("--") ? Long.parseLong(args[2]) : 5_000;
        boolean raw = java.util.Arrays.asList(args).contains("--raw");
        if (!Files.exists(db)) throw new IllegalArgumentException("no such database: " + db);

        var config = new SQLiteConfig();
        config.setReadOnly(true);
        Recording recording;
        try (var connection = config.createConnection("jdbc:sqlite:" + db.toAbsolutePath())) {
            recording = RecordingExporter.export(connection, interval,
                    "Exported from " + db.getFileName() + " every " + interval + " ms");
        }
        if (!raw) recording = Anonymizer.anonymize(recording);
        RecordingIO.write(recording, out);
        System.out.printf("Wrote %d frames (%s) to %s%n", recording.snapshots().size(),
                raw ? "RAW - do not commit" : "anonymized", out.toAbsolutePath());
    }
}
