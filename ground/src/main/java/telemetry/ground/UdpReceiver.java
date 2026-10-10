package telemetry.ground;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.util.function.Consumer;

/**
 * Pulls datagrams off the socket on a dedicated thread and hands them on immediately. It
 * does nothing else, so a slow consumer shows up as a full queue rather than as datagrams
 * silently dropped by the kernel.
 */
final class UdpReceiver implements AutoCloseable {

    private static final int RECEIVE_BUFFER = 4 << 20;
    private static final int MAX_DATAGRAM = 65_536;

    private final DatagramChannel channel;
    private final Consumer<RawFrame> sink;
    private final Thread thread;

    UdpReceiver(InetSocketAddress address, Consumer<RawFrame> sink) throws IOException {
        this.channel = DatagramChannel.open();
        channel.setOption(StandardSocketOptions.SO_RCVBUF, RECEIVE_BUFFER);
        channel.bind(address);
        this.sink = sink;
        this.thread = Thread.ofPlatform().name("udp-receiver").daemon().unstarted(this::run);
    }

    void start() {
        thread.start();
    }

    InetSocketAddress localAddress() throws IOException {
        return (InetSocketAddress) channel.getLocalAddress();
    }

    private void run() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(MAX_DATAGRAM);
        while (channel.isOpen()) {
            try {
                buffer.clear();
                if (channel.receive(buffer) == null) {
                    continue;
                }
                long now = System.nanoTime();
                buffer.flip();
                byte[] data = new byte[buffer.remaining()];
                buffer.get(data);
                sink.accept(new RawFrame(now, data));
            } catch (ClosedChannelException e) {
                return;
            } catch (IOException e) {
                // A transient socket error loses at most one datagram. Keep listening.
            }
        }
    }

    @Override
    public void close() throws IOException {
        channel.close();
        try {
            thread.join(1_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
