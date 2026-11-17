package io.github.jopojp.overseer.core.recording;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes personal data from a recording before it is shared or committed:
 * <ul>
 *   <li>player UUIDs become stable fake UUIDs, player names become {@code Player1}, ...;
 *       both are replaced everywhere they appear (ids, attributes, server MOTD);</li>
 *   <li>block coordinates (device ids, {@code pos}) are shifted by a random offset
 *       (multiples of 16, so chunk alignment is preserved);</li>
 *   <li>the server MOTD is replaced.</li>
 * </ul>
 * Device, network and zone names are kept: they are what makes a recording useful.
 */
public final class Anonymizer {
    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern POSITION = Pattern.compile("(-?\\d+),(-?\\d+),(-?\\d+)");

    private final Map<String, String> uuids = new LinkedHashMap<>();
    private final Map<String, String> names = new LinkedHashMap<>();
    private final int dx;
    private final int dz;

    private Anonymizer(Random random) {
        this.dx = (random.nextInt(4001) - 2000) * 16;
        this.dz = (random.nextInt(4001) - 2000) * 16;
    }

    public static Recording anonymize(Recording recording) {
        return new Anonymizer(new Random()).apply(recording);
    }

    static Recording anonymize(Recording recording, long seed) {
        return new Anonymizer(new Random(seed)).apply(recording);
    }

    private Recording apply(Recording recording) {
        // Pass 1: learn every player (UUID and name) that appears.
        for (Recording.Frame frame : recording.snapshots()) {
            for (Recording.Entry e : frame.resources()) {
                if (frame.provider().equals("server") && e.type().equals("player")) learn(e.id(), e.attributes().get("name"));
                if (e.type().equals("member")) learn(e.attributes().get("player"), e.attributes().get("name"));
                e.attributes().values().forEach(v -> {
                    Matcher m = UUID_PATTERN.matcher(v);
                    while (m.find()) fakeUuid(m.group());
                });
            }
        }
        // Pass 2: rewrite.
        var frames = new ArrayList<Recording.Frame>();
        for (Recording.Frame frame : recording.snapshots()) {
            var entries = new ArrayList<Recording.Entry>();
            for (Recording.Entry e : frame.resources()) {
                Map<String, String> attributes = new TreeMap<>();
                e.attributes().forEach((k, v) -> attributes.put(k, rewriteAttribute(frame.provider(), e.type(), k, v)));
                entries.add(new Recording.Entry(e.type(), rewriteId(e.type(), e.id()), attributes, e.metrics()));
            }
            frames.add(new Recording.Frame(frame.provider(), frame.wall(), frame.uptimeMillis(), frame.session(), entries));
        }
        var h = recording.header();
        return new Recording(new Recording.Header(h.format(), h.version(), h.description(), h.intervalMillis(), true), frames);
    }

    private void learn(String uuid, String name) {
        if (uuid == null || !UUID_PATTERN.matcher(uuid).matches()) return;
        fakeUuid(uuid);
        if (name != null && !name.isBlank()) names.putIfAbsent(name, "Player" + (names.size() + 1));
    }

    private String fakeUuid(String real) {
        return uuids.computeIfAbsent(real.toLowerCase(java.util.Locale.ROOT),
                r -> new UUID(0x0000000000004000L, 0x8000000000000000L | (uuids.size() + 1)).toString());
    }

    private String rewriteId(String type, String id) {
        String out = scrub(id);
        if (type.equals("device")) out = shift(out);
        return out;
    }

    private String rewriteAttribute(String provider, String type, String key, String value) {
        if (provider.equals("server") && type.equals("server") && key.equals("motd")) return "A Minecraft Server";
        String out = scrub(value);
        if (key.equals("pos")) out = shift(out);
        return out;
    }

    /** Replaces known UUIDs and player names anywhere in the text. */
    private String scrub(String text) {
        if (text == null) return null;
        String out = text;
        Matcher m = UUID_PATTERN.matcher(out);
        var sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(fakeUuid(m.group())));
        m.appendTail(sb);
        out = sb.toString();
        for (var name : names.entrySet()) {
            // Whole words only: the player "Dev" must not turn "Device 3" into "Player1ice 3".
            out = out.replaceAll("(?<![A-Za-z0-9_])" + Pattern.quote(name.getKey()) + "(?![A-Za-z0-9_])",
                    Matcher.quoteReplacement(name.getValue()));
        }
        return out;
    }

    private String shift(String text) {
        Matcher m = POSITION.matcher(text);
        var sb = new StringBuilder();
        while (m.find()) {
            int x = Integer.parseInt(m.group(1)) + dx;
            int z = Integer.parseInt(m.group(3)) + dz;
            m.appendReplacement(sb, x + "," + m.group(2) + "," + z);
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Player names this anonymizer replaced, for tests. */
    List<String> replacedNames() {
        return List.copyOf(names.keySet());
    }
}
