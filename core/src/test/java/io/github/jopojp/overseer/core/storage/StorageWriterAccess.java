package io.github.jopojp.overseer.core.storage;

import io.github.jopojp.overseer.core.pipeline.SnapshotQueue;
import io.github.jopojp.overseer.core.provider.ProviderDescriptor;
import io.github.jopojp.overseer.core.provider.ProviderSnapshot;
import io.github.jopojp.overseer.core.time.UptimeClock;
import java.sql.SQLException;
import java.util.List;

/** Lets tests in other packages write synchronously through the storage writer. */
public final class StorageWriterAccess {
    private StorageWriterAccess() {}

    public static StorageWriter create(Database db, SnapshotQueue queue, List<ProviderDescriptor> providers,
            UptimeClock clock, StorageSettings settings) {
        return new StorageWriter(db, queue, providers, clock, settings);
    }

    public static void write(StorageWriter writer, ProviderSnapshot snapshot) throws SQLException {
        writer.write(List.of(snapshot));
    }
}
