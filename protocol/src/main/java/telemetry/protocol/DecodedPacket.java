package telemetry.protocol;

/**
 * A packet that passed every check, with its channel values in dictionary order.
 *
 * @param clock spacecraft clock time from the secondary header, in seconds
 */
public record DecodedPacket(PacketDefinition definition, int sequence, double clock, double[] values)
        implements DecodeResult {

    public int apid() {
        return definition.apid();
    }

    public double value(Channel channel) {
        return values[channel.position()];
    }
}
