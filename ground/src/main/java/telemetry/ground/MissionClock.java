package telemetry.ground;

/**
 * Turns spacecraft clock readings into mission elapsed time. The countdown event tells the
 * ground when liftoff is planned; the liftoff event replaces that with when it happened.
 */
final class MissionClock {

    private double liftoffClock = Double.NaN;
    private boolean confirmed;
    private double latest = Double.NaN;

    void countdown(double plannedLiftoffClock) {
        if (!confirmed) {
            liftoffClock = plannedLiftoffClock;
        }
    }

    void liftoff(double clock) {
        liftoffClock = clock;
        confirmed = true;
    }

    void observe(double clock) {
        if (Double.isNaN(latest) || clock > latest) {
            latest = clock;
        }
    }

    boolean known() {
        return !Double.isNaN(liftoffClock);
    }

    /** Spacecraft clock of the newest packet received. */
    double latest() {
        return latest;
    }

    /** Mission elapsed time of the newest packet, or NaN before the countdown is known. */
    double met() {
        return latest - liftoffClock;
    }

    double metAt(double clock) {
        return clock - liftoffClock;
    }
}
