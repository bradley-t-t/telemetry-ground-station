package telemetry.ground;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Hands the latest snapshot from the processor thread to any number of readers. Readers
 * wait on a condition rather than polling. It uses a lock rather than {@code synchronized}
 * so that waiting virtual threads do not pin their carriers.
 */
public final class SnapshotFeed {

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private Snapshot latest;

    void publish(Snapshot snapshot) {
        lock.lock();
        try {
            latest = snapshot;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public Snapshot latest() {
        lock.lock();
        try {
            return latest;
        } finally {
            lock.unlock();
        }
    }

    /** The first snapshot newer than {@code version}, or null if none arrives in time. */
    public Snapshot awaitNewer(long version, long timeoutMillis) throws InterruptedException {
        long remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        lock.lock();
        try {
            while (latest == null || latest.version() <= version) {
                if (remaining <= 0) {
                    return null;
                }
                remaining = changed.awaitNanos(remaining);
            }
            return latest;
        } finally {
            lock.unlock();
        }
    }
}
