package telemetry.ground;

import telemetry.protocol.Channel;
import telemetry.protocol.ChannelClass;
import telemetry.protocol.DecodedPacket;
import telemetry.protocol.Severity;

/**
 * Catches a sensor that has frozen. A real transducer always carries some noise, so a
 * floating-point sensor channel that has varied before and then reports the identical bit
 * pattern {@value #REPEATS} times in a row is almost certainly not measuring anything. A
 * frozen reading can sit comfortably inside its limits, which is what makes it dangerous.
 */
final class StuckSensorMonitor implements Monitor {

    static final int REPEATS = 40;

    private final Flight flight;
    private final long[] lastBits;
    private final int[] repeats;
    private final boolean[] seen;
    private final boolean[] varied;
    private final boolean[] flagged;

    StuckSensorMonitor(Flight flight) {
        this.flight = flight;
        int size = flight.dictionary.channels().size();
        lastBits = new long[size];
        repeats = new int[size];
        seen = new boolean[size];
        varied = new boolean[size];
        flagged = new boolean[size];
    }

    @Override
    public void onPacket(DecodedPacket packet) {
        double[] current = flight.table.values();
        for (Channel channel : packet.definition().channels()) {
            if (channel.kind() != ChannelClass.SENSOR || !channel.type().isFloatingPoint()) {
                continue;
            }
            int i = channel.index();
            if (!channel.checksEnabled(current)) {
                repeats[i] = 0;
                continue;
            }
            double value = packet.value(channel);
            long bits = Double.doubleToRawLongBits(value);
            if (seen[i] && bits == lastBits[i]) {
                repeats[i]++;
                if (varied[i] && !flagged[i] && repeats[i] >= REPEATS) {
                    flagged[i] = true;
                    flight.alarms.raise("STUCK:" + channel.name(), Severity.CAUTION, channel.name(),
                            channel.name() + " has read exactly " + Format.value(value, channel.units()) + " for "
                                    + REPEATS + " samples in a row; the sensor is probably stuck", packet.clock());
                }
                continue;
            }
            if (seen[i]) {
                varied[i] = true;
            }
            seen[i] = true;
            lastBits[i] = bits;
            repeats[i] = 0;
            if (flagged[i]) {
                flagged[i] = false;
                flight.alarms.clear("STUCK:" + channel.name(), packet.clock());
            }
        }
    }

    boolean suspect(int channel) {
        return flagged[channel];
    }
}
