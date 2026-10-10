package telemetry.protocol;

/**
 * Layout of a CCSDS Space Packet (CCSDS 133.0-B-2) as this system uses it:
 *
 * <pre>
 *  0      primary header, 6 bytes
 *           version (3 bits) = 0, type (1) = 0 telemetry, secondary header flag (1) = 1,
 *           APID (11) | sequence flags (2) = 0b11 unsegmented, sequence count (14) |
 *           packet data length (16) = bytes after the primary header, minus one
 *  6      secondary header, 6 bytes: spacecraft clock as a CCSDS unsegmented time code,
 *           4 bytes of whole seconds and 2 bytes of 1/65536 s
 *  12     payload, laid out by the dictionary
 *  n-2    packet error control, CRC-16/CCITT-FALSE over everything before it
 * </pre>
 */
public final class SpacePacket {

    public static final int PRIMARY_HEADER_LENGTH = 6;
    public static final int TIME_CODE_LENGTH = 6;
    public static final int HEADER_LENGTH = PRIMARY_HEADER_LENGTH + TIME_CODE_LENGTH;
    public static final int CRC_LENGTH = 2;
    public static final int OVERHEAD = HEADER_LENGTH + CRC_LENGTH;
    public static final int MAX_APID = 0x7FF;
    public static final int SEQUENCE_MODULUS = 1 << 14;

    private static final int SECONDARY_HEADER_FLAG = 1 << 11;
    private static final int UNSEGMENTED = 0b11 << 14;
    private static final double FINE_TICKS = 65536.0;

    private SpacePacket() {
    }

    public static int lengthFor(int payloadLength) {
        return OVERHEAD + payloadLength;
    }

    /** Writes the headers and CRC around a payload that is already in place. */
    public static void seal(byte[] packet, int apid, int sequence, double clockSeconds) {
        int length = packet.length;
        putU16(packet, 0, SECONDARY_HEADER_FLAG | (apid & MAX_APID));
        putU16(packet, 2, UNSEGMENTED | (sequence & (SEQUENCE_MODULUS - 1)));
        putU16(packet, 4, length - PRIMARY_HEADER_LENGTH - 1);

        long coarse = (long) Math.floor(clockSeconds);
        long fine = Math.round((clockSeconds - coarse) * FINE_TICKS);
        if (fine == (long) FINE_TICKS) {
            coarse++;
            fine = 0;
        }
        putU16(packet, 6, (int) (coarse >>> 16));
        putU16(packet, 8, (int) coarse);
        putU16(packet, 10, (int) fine);

        putU16(packet, length - CRC_LENGTH, Crc16.compute(packet, 0, length - CRC_LENGTH));
    }

    public static int version(byte[] packet) {
        return u16(packet, 0) >>> 13;
    }

    public static int type(byte[] packet) {
        return (u16(packet, 0) >>> 12) & 1;
    }

    public static boolean hasSecondaryHeader(byte[] packet) {
        return (u16(packet, 0) & SECONDARY_HEADER_FLAG) != 0;
    }

    public static int apid(byte[] packet) {
        return u16(packet, 0) & MAX_APID;
    }

    public static int sequenceFlags(byte[] packet) {
        return u16(packet, 2) >>> 14;
    }

    public static int sequence(byte[] packet) {
        return u16(packet, 2) & (SEQUENCE_MODULUS - 1);
    }

    /** Total length the header claims, in bytes. */
    public static int declaredLength(byte[] packet) {
        return u16(packet, 4) + PRIMARY_HEADER_LENGTH + 1;
    }

    public static double clock(byte[] packet) {
        long coarse = ((long) u16(packet, 6) << 16) | u16(packet, 8);
        return coarse + u16(packet, 10) / FINE_TICKS;
    }

    public static int storedCrc(byte[] packet, int length) {
        return u16(packet, length - CRC_LENGTH);
    }

    static int u16(byte[] buffer, int offset) {
        return ((buffer[offset] & 0xFF) << 8) | (buffer[offset + 1] & 0xFF);
    }

    private static void putU16(byte[] buffer, int offset, int value) {
        buffer[offset] = (byte) (value >>> 8);
        buffer[offset + 1] = (byte) value;
    }
}
