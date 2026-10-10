package telemetry.vehicle;

/** Where encoded packets go: a socket, a list in a test, or an impaired link in between. */
@FunctionalInterface
public interface PacketSink {

    void send(byte[] packet);
}
