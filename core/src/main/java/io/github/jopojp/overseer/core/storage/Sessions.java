package io.github.jopojp.overseer.core.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Uptime sessions: map server uptime to calendar time and find offline gaps (ADR-0005). */
public final class Sessions {
    private Sessions() {}

    public record Session(int number, long startedWall, long startedUptime, long lastWall, long lastUptime) {}

    public static List<Session> list(Connection connection) throws SQLException {
        var result = new ArrayList<Session>();
        try (var st = connection.createStatement(); var rs = st.executeQuery("SELECT session, started_wall_ms,"
                + " started_uptime_ms, last_wall_ms, last_uptime_ms FROM uptime_session ORDER BY session")) {
            while (rs.next()) {
                result.add(new Session(rs.getInt(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)));
            }
        }
        return result;
    }

    /** The session in effect at {@code uptime} (the last one that started at or before it). */
    public static Session at(List<Session> sessions, long uptime) {
        Session match = sessions.isEmpty() ? new Session(1, 0, 0, 0, 0) : sessions.get(0);
        for (Session s : sessions) if (s.startedUptime() <= uptime) match = s;
        return match;
    }

    /** Calendar time (epoch ms) at {@code uptime}. */
    public static long wallAt(List<Session> sessions, long uptime) {
        Session s = at(sessions, uptime);
        return s.startedWall() + (uptime - s.startedUptime());
    }
}
