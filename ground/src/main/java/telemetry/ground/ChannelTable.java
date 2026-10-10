package telemetry.ground;

import java.util.Arrays;
import telemetry.protocol.Channel;
import telemetry.protocol.DecodedPacket;
import telemetry.protocol.Dictionary;
import telemetry.protocol.SpacePacket;

/** The latest value of every channel, and when each packet type last arrived. */
final class ChannelTable {

    static final long NEVER = Long.MIN_VALUE;

    private final double[] values;
    private final double[] lastClock = new double[SpacePacket.MAX_APID + 1];
    private final long[] lastReceived = new long[SpacePacket.MAX_APID + 1];

    ChannelTable(Dictionary dictionary) {
        values = new double[dictionary.channels().size()];
        Arrays.fill(values, Double.NaN);
        Arrays.fill(lastClock, Double.NaN);
        Arrays.fill(lastReceived, NEVER);
    }

    /**
     * Applies a packet's values unless the table already holds a newer packet of the same
     * type, which happens when the link reorders packets.
     *
     * @return whether the values were applied
     */
    boolean apply(DecodedPacket packet, long receivedNanos) {
        int apid = packet.apid();
        lastReceived[apid] = receivedNanos;
        if (packet.clock() < lastClock[apid]) {
            return false;
        }
        lastClock[apid] = packet.clock();
        for (Channel channel : packet.definition().channels()) {
            values[channel.index()] = packet.value(channel);
        }
        return true;
    }

    double[] values() {
        return values;
    }

    double value(Channel channel) {
        return values[channel.index()];
    }

    long lastReceived(int apid) {
        return lastReceived[apid];
    }
}
