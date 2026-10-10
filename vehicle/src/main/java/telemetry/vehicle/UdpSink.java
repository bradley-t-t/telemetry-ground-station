package telemetry.vehicle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;

/** Sends each packet as one UDP datagram. */
public final class UdpSink implements PacketSink, AutoCloseable {

    private final DatagramChannel channel;
    private final InetSocketAddress target;

    public UdpSink(InetSocketAddress target) throws IOException {
        this.channel = DatagramChannel.open();
        this.target = target;
    }

    @Override
    public void send(byte[] packet) {
        try {
            channel.send(ByteBuffer.wrap(packet), target);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
