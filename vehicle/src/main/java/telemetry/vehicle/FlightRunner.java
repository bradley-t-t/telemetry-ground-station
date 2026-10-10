package telemetry.vehicle;

import java.util.concurrent.locks.LockSupport;

/** Runs a flight either as fast as the CPU allows or paced against the wall clock. */
public final class FlightRunner {

    private FlightRunner() {
    }

    public static void runToCompletion(FlightSimulation flight) {
        while (!flight.finished()) {
            flight.step();
        }
    }

    /**
     * Steps the flight so that {@code speed} simulated seconds pass per wall-clock second.
     * Returns early, with the interrupt flag set, if the thread is interrupted.
     */
    public static void runPaced(FlightSimulation flight, double speed) {
        long start = System.nanoTime();
        double startTime = flight.time();
        while (!flight.finished()) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            flight.step();
            long due = start + (long) ((flight.time() - startTime) / speed * 1e9);
            long wait = due - System.nanoTime();
            if (wait > 0) {
                LockSupport.parkNanos(wait);
            }
        }
    }
}
