package telemetry.ground;

import telemetry.protocol.Rejected;
import telemetry.protocol.SpacePacket;

/** Everything the ground knows about the health of the downlink itself. */
final class LinkStatistics {

    private final SequenceTracker[] trackers = new SequenceTracker[SpacePacket.MAX_APID + 1];
    private final RateMeter packetRate = new RateMeter();
    private final RateMeter byteRate = new RateMeter();
    private final LatencyHistogram latency = new LatencyHistogram();
    private long frames;
    private long bytes;
    private long crcErrors;
    private long malformed;
    private long unknownApid;

    void frame(long nanos, int length) {
        frames++;
        bytes += length;
        packetRate.add(nanos, 1);
        byteRate.add(nanos, length);
    }

    void rejected(Rejected rejected) {
        switch (rejected.reason()) {
            case CRC_MISMATCH -> crcErrors++;
            case UNKNOWN_APID -> unknownApid++;
            case TRUNCATED, LENGTH_MISMATCH, BAD_HEADER, PAYLOAD_SIZE -> malformed++;
        }
    }

    SequenceTracker.Outcome sequence(int apid, int sequence) {
        SequenceTracker tracker = trackers[apid];
        if (tracker == null) {
            tracker = new SequenceTracker();
            trackers[apid] = tracker;
        }
        return tracker.accept(sequence);
    }

    void recordLatency(long nanos) {
        latency.record(nanos);
    }

    SequenceTracker tracker(int apid) {
        return trackers[apid];
    }

    long frames() {
        return frames;
    }

    long bytes() {
        return bytes;
    }

    long crcErrors() {
        return crcErrors;
    }

    long malformed() {
        return malformed;
    }

    long unknownApid() {
        return unknownApid;
    }

    long lost() {
        long total = 0;
        for (SequenceTracker tracker : trackers) {
            if (tracker != null) {
                total += tracker.lost();
            }
        }
        return total;
    }

    long received() {
        long total = 0;
        for (SequenceTracker tracker : trackers) {
            if (tracker != null) {
                total += tracker.received();
            }
        }
        return total;
    }

    long duplicates() {
        long total = 0;
        for (SequenceTracker tracker : trackers) {
            if (tracker != null) {
                total += tracker.duplicates();
            }
        }
        return total;
    }

    long late() {
        long total = 0;
        for (SequenceTracker tracker : trackers) {
            if (tracker != null) {
                total += tracker.late();
            }
        }
        return total;
    }

    /** Lost packets as a share of all packets sent, from the sequence counts. */
    double lossPercent() {
        long lost = lost();
        long sent = received() + lost;
        return sent == 0 ? 0 : 100.0 * lost / sent;
    }

    double packetsPerSecond(long nanos) {
        return packetRate.perSecond(nanos);
    }

    double bytesPerSecond(long nanos) {
        return byteRate.perSecond(nanos);
    }

    LatencyHistogram latency() {
        return latency;
    }
}
