package telemetry.protocol;

/** A datagram the decoder refused. {@code apid} is -1 when the header could not be trusted. */
public record Rejected(Reason reason, int apid, int length) implements DecodeResult {

    public enum Reason {
        /** Shorter than the smallest possible packet. */
        TRUNCATED,
        /** The length field disagrees with the datagram size. */
        LENGTH_MISMATCH,
        /** The packet error control field does not match the contents. */
        CRC_MISMATCH,
        /** Wrong version, type, secondary header flag or sequence flags. */
        BAD_HEADER,
        /** The dictionary has no packet with this APID. */
        UNKNOWN_APID,
        /** The payload is not the size the dictionary defines for this APID. */
        PAYLOAD_SIZE
    }
}
