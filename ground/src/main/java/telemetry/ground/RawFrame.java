package telemetry.ground;

/**
 * One datagram as it came off the wire.
 *
 * @param receivedNanos {@link System#nanoTime()} at receipt, or the recorded receipt time
 *                      when the frame is replayed from an archive
 */
public record RawFrame(long receivedNanos, byte[] data) {
}
