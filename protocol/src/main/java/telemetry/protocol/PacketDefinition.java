package telemetry.protocol;

import java.util.List;

/**
 * A packet type: its application process identifier, the rate it is sent at, and its
 * channels in wire order.
 */
public record PacketDefinition(int apid, String name, double rateHz, String description,
                               List<Channel> channels, int payloadLength) {

    public PacketDefinition {
        channels = List.copyOf(channels);
    }

    public int packetLength() {
        return SpacePacket.lengthFor(payloadLength);
    }

    public boolean isPeriodic() {
        return rateHz > 0;
    }
}
