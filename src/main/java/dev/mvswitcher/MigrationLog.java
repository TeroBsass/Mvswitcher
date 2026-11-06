package dev.mvswitcher;

import java.time.Instant;
import java.util.Objects;

public final class MigrationLog {
    public enum Level { INFO, SUCCESS, WARN, ERROR, DEBUG }

    public record Event(Instant timestamp, Level level, String message) {
        public Event {
            Objects.requireNonNull(timestamp);
            Objects.requireNonNull(level);
            Objects.requireNonNull(message);
        }

        @Override public String toString() {
            return timestamp + " [" + level + "] " + message;
        }
    }

    @FunctionalInterface
    public interface Listener {
        Listener NONE = event -> {};
        void onLog(Event event);
    }

    private MigrationLog() {}

    public static void log(Listener listener, Level level, String message) {
        (listener == null ? Listener.NONE : listener).onLog(new Event(Instant.now(), level, message));
    }
}
