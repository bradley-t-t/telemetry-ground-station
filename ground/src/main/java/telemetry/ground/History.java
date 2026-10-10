package telemetry.ground;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Every channel sampled ten times a second of mission time, for plots and the post-flight
 * report. The processor thread writes it; dashboard connections read it, so access goes
 * through a read-write lock.
 */
public final class History {

    static final double INTERVAL = 0.1;
    private static final int CAPACITY = 15_000;

    /** Rows from a cursor onwards. Each row is mission time followed by the requested channels. */
    public record Slice(long next, double[][] rows) {
    }

    private final double[] met = new double[CAPACITY];
    private final double[][] rows = new double[CAPACITY][];
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private long count;
    private double nextClock = Double.NaN;

    void sample(MissionClock clock, double[] values) {
        if (!clock.known()) {
            return;
        }
        double now = clock.latest();
        if (!Double.isNaN(nextClock) && now < nextClock) {
            return;
        }
        nextClock = (Math.floor(now / INTERVAL) + 1) * INTERVAL;
        double[] copy = values.clone();
        lock.writeLock().lock();
        try {
            int slot = (int) (count % CAPACITY);
            met[slot] = clock.met();
            rows[slot] = copy;
            count++;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public long count() {
        lock.readLock().lock();
        try {
            return count;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Rows after {@code cursor} for the given channel indexes, thinned evenly to at most
     * {@code maxRows} so a client joining late gets the whole flight in one message.
     */
    public Slice since(long cursor, int[] channels, int maxRows) {
        lock.readLock().lock();
        try {
            long start = Math.max(cursor, count - CAPACITY);
            long available = Math.max(0, count - start);
            long stride = Math.max(1, (available + maxRows - 1) / maxRows);
            int size = (int) ((available + stride - 1) / stride);
            double[][] out = new double[size][];
            for (int i = 0; i < size; i++) {
                int slot = (int) ((start + i * stride) % CAPACITY);
                double[] row = new double[channels.length + 1];
                row[0] = met[slot];
                for (int c = 0; c < channels.length; c++) {
                    row[c + 1] = rows[slot][channels[c]];
                }
                out[i] = row;
            }
            return new Slice(count, out);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** One channel's full history as {@code [met, value]} pairs. */
    public double[][] series(int channel) {
        lock.readLock().lock();
        try {
            long start = Math.max(0, count - CAPACITY);
            int size = (int) (count - start);
            double[][] out = new double[2][size];
            for (int i = 0; i < size; i++) {
                int slot = (int) ((start + i) % CAPACITY);
                out[0][i] = met[slot];
                out[1][i] = rows[slot][channel];
            }
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Largest value a channel reached while {@code gate} held the given value, or NaN. */
    double max(int channel, int gate, double gateValue) {
        double max = Double.NaN;
        double[][] values = series(channel);
        double[][] gates = gate < 0 ? null : series(gate);
        for (int i = 0; i < values[1].length; i++) {
            if (gates != null && gates[1][i] != gateValue) {
                continue;
            }
            double v = values[1][i];
            if (!Double.isNaN(v) && (Double.isNaN(max) || v > max)) {
                max = v;
            }
        }
        return max;
    }
}
