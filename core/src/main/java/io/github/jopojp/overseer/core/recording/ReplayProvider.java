package io.github.jopojp.overseer.core.recording;

import io.github.jopojp.overseer.core.provider.AccessPolicy;
import io.github.jopojp.overseer.core.provider.Capability;
import io.github.jopojp.overseer.core.provider.DataProvider;
import io.github.jopojp.overseer.core.provider.MetricDescriptor;
import io.github.jopojp.overseer.core.provider.MetricKind;
import io.github.jopojp.overseer.core.provider.ProviderDescriptor;
import io.github.jopojp.overseer.core.provider.ProviderSnapshot;
import io.github.jopojp.overseer.core.time.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * Plays back one provider's frames from a recording, in a loop, at the recorded pace.
 * Used by the simulator and tests. Everyone logged in may see everything: replayed data
 * is not tied to the viewer's account.
 */
public final class ReplayProvider implements DataProvider {
    private final ProviderDescriptor descriptor;
    private final List<Recording.Frame> frames;
    private int next;

    public ReplayProvider(Recording recording, String provider) {
        this.frames = recording.snapshots().stream().filter(f -> f.provider().equals(provider)).toList();
        if (frames.isEmpty()) throw new IllegalArgumentException("no frames for provider " + provider);
        var metrics = new TreeSet<String>();
        frames.forEach(f -> f.resources().forEach(e -> metrics.addAll(e.metrics().keySet())));
        var descriptors = new ArrayList<MetricDescriptor>();
        metrics.forEach(m -> descriptors.add(new MetricDescriptor(m, MetricKind.GAUGE, "", 0.01)));
        int ticks = (int) Math.max(1, recording.header().intervalMillis() / 50);
        this.descriptor = new ProviderDescriptor(provider, provider + " (replay)",
                Set.of(Capability.METRICS, Capability.EVENTS), descriptors, ticks, TimeUnit.MILLISECONDS.toNanos(5));
    }

    /** One replay provider per provider id found in the recording. */
    public static List<DataProvider> all(Recording recording) {
        var ids = new TreeSet<String>();
        recording.snapshots().forEach(f -> ids.add(f.provider()));
        return ids.stream().<DataProvider>map(id -> new ReplayProvider(recording, id)).toList();
    }

    @Override
    public ProviderDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ProviderSnapshot collect(Timestamp at) {
        Recording.Frame frame = frames.get(next);
        next = (next + 1) % frames.size();
        return frame.toSnapshot(at);
    }

    @Override
    public AccessPolicy access() {
        return AccessPolicy.ALLOW_AUTHENTICATED;
    }
}
