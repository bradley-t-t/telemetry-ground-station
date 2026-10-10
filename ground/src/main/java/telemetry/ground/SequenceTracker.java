package telemetry.ground;

import telemetry.protocol.SpacePacket;

/**
 * Follows one APID's 14-bit sequence count. A jump forward counts the skipped packets as
 * lost; a packet from behind is either a duplicate, if that count was already seen, or a
 * late arrival that was counted as lost and now is not.
 */
final class SequenceTracker {

    enum Outcome {
        IN_ORDER, GAP, LATE, DUPLICATE
    }

    private static final int MODULUS = SpacePacket.SEQUENCE_MODULUS;
    private static final int MASK = MODULUS - 1;
    private static final int HALF = MODULUS / 2;

    private final long[] seen = new long[MODULUS / 64];
    private int expected = -1;
    private long received;
    private long lost;
    private long duplicates;
    private long late;

    Outcome accept(int sequence) {
        if (expected < 0) {
            mark(sequence);
            expected = (sequence + 1) & MASK;
            received++;
            return Outcome.IN_ORDER;
        }
        int ahead = (sequence - expected) & MASK;
        if (ahead < HALF) {
            for (int s = expected; s != sequence; s = (s + 1) & MASK) {
                unmark(s);
            }
            lost += ahead;
            mark(sequence);
            expected = (sequence + 1) & MASK;
            received++;
            return ahead == 0 ? Outcome.IN_ORDER : Outcome.GAP;
        }
        if (isMarked(sequence)) {
            duplicates++;
            return Outcome.DUPLICATE;
        }
        mark(sequence);
        received++;
        late++;
        if (lost > 0) {
            lost--;
        }
        return Outcome.LATE;
    }

    long received() {
        return received;
    }

    long lost() {
        return lost;
    }

    long duplicates() {
        return duplicates;
    }

    long late() {
        return late;
    }

    private void mark(int sequence) {
        seen[sequence >>> 6] |= 1L << (sequence & 63);
    }

    private void unmark(int sequence) {
        seen[sequence >>> 6] &= ~(1L << (sequence & 63));
    }

    private boolean isMarked(int sequence) {
        return (seen[sequence >>> 6] & (1L << (sequence & 63))) != 0;
    }
}
