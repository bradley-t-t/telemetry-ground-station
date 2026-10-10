package telemetry.ground;

import java.util.Arrays;

/** Counts events over the last second in ten 100 ms buckets. */
final class RateMeter {

    private static final long BUCKET_NANOS = 100_000_000L;
    private static final int BUCKETS = 10;

    private final long[] counts = new long[BUCKETS];
    private final long[] slots = new long[BUCKETS];

    RateMeter() {
        Arrays.fill(slots, Long.MIN_VALUE);
    }

    void add(long nanos, long amount) {
        long slot = Math.floorDiv(nanos, BUCKET_NANOS);
        int i = Math.floorMod(slot, BUCKETS);
        if (slots[i] != slot) {
            slots[i] = slot;
            counts[i] = 0;
        }
        counts[i] += amount;
    }

    double perSecond(long nanos) {
        long current = Math.floorDiv(nanos, BUCKET_NANOS);
        long total = 0;
        for (int i = 0; i < BUCKETS; i++) {
            if (slots[i] > current - BUCKETS && slots[i] <= current) {
                total += counts[i];
            }
        }
        return total;
    }
}
