package telemetry.ground;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.locks.LockSupport;
import telemetry.protocol.Dictionary;

/** Plays a recorded archive back through the ground station pipeline. */
public final class Replay {

    private static final long TICK_NANOS = 50_000_000L;

    private Replay() {
    }

    /**
     * Processes an archive as fast as the CPU allows, ticking the monitors on the recorded
     * clock, and returns the processor holding the result.
     */
    public static TelemetryProcessor process(Path archive, Dictionary dictionary) throws IOException {
        TelemetryProcessor processor = new TelemetryProcessor(dictionary);
        long nextTick = Long.MIN_VALUE;
        long last = 0;
        try (ArchiveReader reader = new ArchiveReader(archive)) {
            RawFrame frame;
            while ((frame = reader.next()) != null) {
                last = frame.receivedNanos();
                if (nextTick == Long.MIN_VALUE) {
                    nextTick = last;
                }
                while (last >= nextTick) {
                    processor.tick(nextTick);
                    nextTick += TICK_NANOS;
                }
                processor.process(frame);
            }
        }
        processor.tick(last);
        return processor;
    }

    /** Feeds an archive into a running station at {@code speed} times its recorded pace. */
    public static void feed(Path archive, GroundStation station, double speed) throws IOException {
        long start = System.nanoTime();
        try (ArchiveReader reader = new ArchiveReader(archive)) {
            RawFrame frame;
            while ((frame = reader.next()) != null) {
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                long due = start + (long) (frame.receivedNanos() / speed);
                long wait = due - System.nanoTime();
                if (wait > 0) {
                    LockSupport.parkNanos(wait);
                }
                station.inject(frame.data());
            }
        }
    }
}
