package telemetry.ground;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SequenceTrackerTest {

    @Test
    void countsGapsAsLoss() {
        SequenceTracker tracker = new SequenceTracker();
        tracker.accept(0);
        tracker.accept(1);
        assertEquals(SequenceTracker.Outcome.GAP, tracker.accept(5));
        assertEquals(3, tracker.lost());
        assertEquals(3, tracker.received());
    }

    @Test
    void aLatePacketIsNoLongerLost() {
        SequenceTracker tracker = new SequenceTracker();
        tracker.accept(10);
        tracker.accept(12);
        assertEquals(1, tracker.lost());
        assertEquals(SequenceTracker.Outcome.LATE, tracker.accept(11));
        assertEquals(0, tracker.lost());
        assertEquals(1, tracker.late());
    }

    @Test
    void recognisesDuplicates() {
        SequenceTracker tracker = new SequenceTracker();
        tracker.accept(7);
        tracker.accept(8);
        assertEquals(SequenceTracker.Outcome.DUPLICATE, tracker.accept(8));
        assertEquals(SequenceTracker.Outcome.DUPLICATE, tracker.accept(7));
        assertEquals(2, tracker.duplicates());
        assertEquals(0, tracker.lost());
    }

    @Test
    void wrapsAtFourteenBitsWithoutCountingLoss() {
        SequenceTracker tracker = new SequenceTracker();
        for (int i = 16_380; i < 16_384; i++) {
            tracker.accept(i);
        }
        assertEquals(SequenceTracker.Outcome.IN_ORDER, tracker.accept(0));
        assertEquals(SequenceTracker.Outcome.IN_ORDER, tracker.accept(1));
        assertEquals(0, tracker.lost());
    }

    @Test
    void aGapAcrossTheWrapIsCounted() {
        SequenceTracker tracker = new SequenceTracker();
        tracker.accept(16_382);
        assertEquals(SequenceTracker.Outcome.GAP, tracker.accept(2));
        assertEquals(3, tracker.lost());
    }
}
