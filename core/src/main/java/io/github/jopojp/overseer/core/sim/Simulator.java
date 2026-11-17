package io.github.jopojp.overseer.core.sim;

import io.github.jopojp.overseer.core.OverseerRuntime;
import io.github.jopojp.overseer.core.auth.PlayerRef;
import io.github.jopojp.overseer.core.http.HttpConfig;
import io.github.jopojp.overseer.core.provider.DataProvider;
import io.github.jopojp.overseer.core.recording.Recording;
import io.github.jopojp.overseer.core.recording.RecordingIO;
import io.github.jopojp.overseer.core.recording.ReplayProvider;
import io.github.jopojp.overseer.core.storage.StorageSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Runs Overseer without Minecraft: replays recordings as live providers, at 20 ticks per
 * second, with the real storage, API and SSE. For UI work ({@code npm run dev} proxies to
 * it) and for trying features against real data.
 *
 * <p>{@code ./gradlew :core:runSimulator} (recordings from {@code fixtures/recordings}),
 * or {@code --args="<dir or .jsonl> ..."}. Everyone is treated as an operator.
 */
public final class Simulator {
    static final UUID DEVELOPER = UUID.fromString("00000000-0000-4000-8000-00000000de70");

    private Simulator() {}

    public static void main(String[] args) throws Exception {
        List<Path> files = new ArrayList<>();
        for (String arg : args.length > 0 ? args : new String[] {"../fixtures/recordings"}) {
            Path path = Path.of(arg);
            if (Files.isDirectory(path)) {
                try (Stream<Path> list = Files.list(path)) {
                    list.filter(p -> p.toString().endsWith(".jsonl")).sorted().forEach(files::add);
                }
            } else {
                files.add(path);
            }
        }
        if (files.isEmpty()) throw new IllegalArgumentException("no recordings found in " + List.of(args));

        List<DataProvider> providers = new ArrayList<>();
        var seen = new java.util.HashSet<String>();
        for (Path file : files) {
            Recording recording = RecordingIO.read(file);
            for (DataProvider provider : ReplayProvider.all(recording)) {
                if (seen.add(provider.descriptor().id())) providers.add(provider);
            }
            System.out.printf("Replaying %s (%d frames)%n", file.getFileName(), recording.snapshots().size());
        }

        Path data = Path.of("build/simulator");
        var config = new OverseerRuntime.Config(data, HttpConfig.DEFAULTS, StorageSettings.DEFAULTS,
                Duration.ofDays(30), 1024, "simulator", "overseer-web");
        OverseerRuntime runtime = OverseerRuntime.start(config, providers, player -> true);

        // The simulator's "server thread".
        var ticker = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "simulator-tick"));
        ticker.scheduleAtFixedRate(runtime::tick, 0, 50, TimeUnit.MILLISECONDS);

        String token = runtime.auth().issueConsoleLogin(new PlayerRef(DEVELOPER, "Developer"));
        String path = "/api/v1/auth/login?token=" + token;
        System.out.println();
        System.out.println("Overseer simulator on http://127.0.0.1:" + runtime.httpPort());
        System.out.println("Log in (one-time, 10 min):");
        System.out.println("  with npm run dev:  http://127.0.0.1:5173" + path);
        System.out.println("  built UI:          http://127.0.0.1:" + runtime.httpPort() + path);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            ticker.shutdownNow();
            runtime.close();
        }));
        Thread.currentThread().join();
    }
}
