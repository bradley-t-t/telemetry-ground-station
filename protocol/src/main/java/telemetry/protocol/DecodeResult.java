package telemetry.protocol;

/** The outcome of decoding one datagram: a packet, or the reason it was refused. */
public sealed interface DecodeResult permits DecodedPacket, Rejected {
}
