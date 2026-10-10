package telemetry.ground;

/**
 * A log-scale histogram of durations from 1 microsecond to about 17 seconds, four buckets
 * per doubling, so any percentile is reported within 19% of the true value.
 */
final class LatencyHistogram {

    private static final int PER_OCTAVE = 4;
    private static final int BUCKETS = 24 * PER_OCTAVE;

    private final long[] counts = new long[BUCKETS];
    private long total;
    private long maxNanos;

    void record(long nanos) {
        double micros = Math.max(1, nanos / 1000.0);
        int bucket = (int) Math.min(BUCKETS - 1, Math.floor(Math.log(micros) / Math.log(2) * PER_OCTAVE));
        counts[bucket]++;
        total++;
        maxNanos = Math.max(maxNanos, nanos);
    }

    long count() {
        return total;
    }

    /** The upper edge of the bucket holding the given quantile, in microseconds. */
    double percentileMicros(double quantile) {
        if (total == 0) {
            return Double.NaN;
        }
        long rank = (long) Math.ceil(quantile * total);
        long seen = 0;
        for (int i = 0; i < BUCKETS; i++) {
            seen += counts[i];
            if (seen >= rank) {
                return Math.pow(2, (i + 1) / (double) PER_OCTAVE);
            }
        }
        return maxNanos / 1000.0;
    }

    double maxMicros() {
        return maxNanos / 1000.0;
    }
}
