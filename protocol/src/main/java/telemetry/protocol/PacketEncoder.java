package telemetry.protocol;

/**
 * Builds packets of one type. Channel values are written straight into a reusable buffer;
 * {@link #encode(double)} seals it, advances the sequence count and returns a copy.
 */
public final class PacketEncoder {

    private final PacketDefinition definition;
    private final byte[] buffer;
    private int sequence;

    public PacketEncoder(PacketDefinition definition) {
        this.definition = definition;
        this.buffer = new byte[definition.packetLength()];
    }

    public PacketDefinition definition() {
        return definition;
    }

    public PacketEncoder set(Channel channel, double value) {
        if (channel.apid() != definition.apid()) {
            throw new IllegalArgumentException(channel.name() + " is not part of " + definition.name());
        }
        channel.type().write(buffer, channel.offset(), value);
        return this;
    }

    public byte[] encode(double clockSeconds) {
        SpacePacket.seal(buffer, definition.apid(), sequence, clockSeconds);
        sequence = (sequence + 1) % SpacePacket.SEQUENCE_MODULUS;
        return buffer.clone();
    }
}
