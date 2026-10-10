package telemetry.ground;

import telemetry.protocol.Severity;

/**
 * An alarm on the ground station's list. It stays there until an operator has acknowledged
 * it and its condition has gone away, in either order.
 */
public final class Alarm {

    public enum State {
        /** The condition holds now. */
        ACTIVE,
        /** The condition has cleared but nobody has acknowledged the alarm yet. */
        RETURNED
    }

    final int id;
    final String key;
    final String channel;
    final double raisedClock;
    Severity severity;
    State state = State.ACTIVE;
    boolean acknowledged;
    String message;

    Alarm(int id, String key, String channel, Severity severity, String message, double raisedClock) {
        this.id = id;
        this.key = key;
        this.channel = channel;
        this.severity = severity;
        this.message = message;
        this.raisedClock = raisedClock;
    }

    public int id() {
        return id;
    }

    public String key() {
        return key;
    }

    public String channel() {
        return channel;
    }

    public Severity severity() {
        return severity;
    }

    public State state() {
        return state;
    }

    public boolean acknowledged() {
        return acknowledged;
    }

    public String message() {
        return message;
    }

    public double raisedClock() {
        return raisedClock;
    }
}
