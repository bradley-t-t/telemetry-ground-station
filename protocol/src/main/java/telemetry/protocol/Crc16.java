package telemetry.protocol;

/**
 * CRC-16/CCITT-FALSE: polynomial 0x1021, initial value 0xFFFF, no reflection. This is the
 * checksum CCSDS recommends for the packet error control field.
 */
public final class Crc16 {

    private static final int[] TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int crc = i << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
            }
            TABLE[i] = crc & 0xFFFF;
        }
    }

    private Crc16() {
    }

    public static int compute(byte[] data, int offset, int length) {
        int crc = 0xFFFF;
        for (int i = offset; i < offset + length; i++) {
            crc = ((crc << 8) ^ TABLE[((crc >>> 8) ^ data[i]) & 0xFF]) & 0xFFFF;
        }
        return crc;
    }
}
