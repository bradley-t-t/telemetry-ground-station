package telemetry.ground;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import telemetry.protocol.Severity;

/**
 * Owns the alarm list. Monitors raise and clear alarms by key; operators acknowledge them
 * by id. Every change is also written to a log the post-flight report reads.
 */
final class AlarmManager {

    enum Change {
        RAISED, ESCALATED, CLEARED, ACKNOWLEDGED
    }

    record LogEntry(double clock, Change change, String key, Severity severity, String message) {
    }

    private static final Comparator<Alarm> DISPLAY_ORDER = Comparator
            .comparing((Alarm a) -> a.state == Alarm.State.ACTIVE ? 0 : 1)
            .thenComparing(a -> -a.severity.ordinal())
            .thenComparingDouble(a -> a.raisedClock);

    private final Map<String, Alarm> alarms = new LinkedHashMap<>();
    private final List<LogEntry> log = new ArrayList<>();
    private int nextId = 1;

    void raise(String key, Severity severity, String channel, String message, double clock) {
        Alarm alarm = alarms.get(key);
        if (alarm == null) {
            alarms.put(key, new Alarm(nextId++, key, channel, severity, message, clock));
            log.add(new LogEntry(clock, Change.RAISED, key, severity, message));
            return;
        }
        boolean returning = alarm.state == Alarm.State.RETURNED;
        boolean escalating = severity.compareTo(alarm.severity) > 0;
        alarm.state = Alarm.State.ACTIVE;
        alarm.severity = severity;
        alarm.message = message;
        if (returning || escalating) {
            alarm.acknowledged = false;
            log.add(new LogEntry(clock, returning ? Change.RAISED : Change.ESCALATED, key, severity, message));
        }
    }

    void clear(String key, double clock) {
        Alarm alarm = alarms.get(key);
        if (alarm == null || alarm.state != Alarm.State.ACTIVE) {
            return;
        }
        log.add(new LogEntry(clock, Change.CLEARED, key, alarm.severity, alarm.message));
        if (alarm.acknowledged) {
            alarms.remove(key);
        } else {
            alarm.state = Alarm.State.RETURNED;
        }
    }

    boolean acknowledge(int id, double clock) {
        Iterator<Alarm> iterator = alarms.values().iterator();
        while (iterator.hasNext()) {
            Alarm alarm = iterator.next();
            if (alarm.id != id) {
                continue;
            }
            if (!alarm.acknowledged) {
                alarm.acknowledged = true;
                log.add(new LogEntry(clock, Change.ACKNOWLEDGED, alarm.key, alarm.severity, alarm.message));
            }
            if (alarm.state == Alarm.State.RETURNED) {
                iterator.remove();
            }
            return true;
        }
        return false;
    }

    void acknowledgeAll(double clock) {
        for (Alarm alarm : List.copyOf(alarms.values())) {
            acknowledge(alarm.id, clock);
        }
    }

    boolean isActive(String key) {
        Alarm alarm = alarms.get(key);
        return alarm != null && alarm.state == Alarm.State.ACTIVE;
    }

    List<Alarm> current() {
        List<Alarm> list = new ArrayList<>(alarms.values());
        list.sort(DISPLAY_ORDER);
        return list;
    }

    List<LogEntry> log() {
        return log;
    }
}
