package telemetry.ground;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import telemetry.protocol.FlightEvent;

/**
 * Flight events in time order. The vehicle sends each event several times, so an event is
 * keyed by its code and the clock time it happened, and repeats are dropped.
 */
final class EventLog {

    record Entry(FlightEvent event, double clock, double arg, boolean ground) {
    }

    private final List<Entry> entries = new ArrayList<>();
    private final Set<String> seen = new HashSet<>();

    /** Records an event and returns true, or returns false if it was already recorded. */
    boolean record(FlightEvent event, double clock, double arg, boolean ground) {
        if (!seen.add(event.code() + "@" + Math.round(clock * 1000))) {
            return false;
        }
        entries.add(new Entry(event, clock, arg, ground));
        entries.sort(Comparator.comparingDouble(Entry::clock));
        return true;
    }

    boolean contains(FlightEvent event) {
        for (Entry entry : entries) {
            if (entry.event() == event) {
                return true;
            }
        }
        return false;
    }

    Entry first(FlightEvent event) {
        for (Entry entry : entries) {
            if (entry.event() == event) {
                return entry;
            }
        }
        return null;
    }

    List<Entry> entries() {
        return entries;
    }
}
