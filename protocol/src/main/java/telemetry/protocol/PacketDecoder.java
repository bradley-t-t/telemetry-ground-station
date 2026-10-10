package telemetry.protocol;

import java.util.List;

/**
 * Validates and decodes datagrams against the dictionary. The CRC is checked before any
 * header field is trusted, so a corrupted packet is counted as corrupted rather than as
 * a packet from some other APID.
 */
public final class PacketDecoder {

    private final Dictionary dictionary;

    public PacketDecoder(Dictionary dictionary) {
        this.dictionary = dictionary;
    }

    public DecodeResult decode(byte[] data) {
        int length = data.length;
        if (length < SpacePacket.OVERHEAD) {
            return new Rejected(Rejected.Reason.TRUNCATED, -1, length);
        }
        if (SpacePacket.declaredLength(data) != length) {
            return new Rejected(Rejected.Reason.LENGTH_MISMATCH, -1, length);
        }
        if (Crc16.compute(data, 0, length - SpacePacket.CRC_LENGTH) != SpacePacket.storedCrc(data, length)) {
            return new Rejected(Rejected.Reason.CRC_MISMATCH, -1, length);
        }
        int apid = SpacePacket.apid(data);
        if (SpacePacket.version(data) != 0 || SpacePacket.type(data) != 0
                || !SpacePacket.hasSecondaryHeader(data) || SpacePacket.sequenceFlags(data) != 0b11) {
            return new Rejected(Rejected.Reason.BAD_HEADER, apid, length);
        }
        PacketDefinition definition = dictionary.packet(apid);
        if (definition == null) {
            return new Rejected(Rejected.Reason.UNKNOWN_APID, apid, length);
        }
        if (definition.packetLength() != length) {
            return new Rejected(Rejected.Reason.PAYLOAD_SIZE, apid, length);
        }
        List<Channel> channels = definition.channels();
        double[] values = new double[channels.size()];
        for (int i = 0; i < values.length; i++) {
            Channel channel = channels.get(i);
            values[i] = channel.type().read(data, channel.offset());
        }
        return new DecodedPacket(definition, SpacePacket.sequence(data), SpacePacket.clock(data), values);
    }
}
