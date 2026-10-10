package telemetry.ground;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Records every frame exactly as received, valid or not, so a session can be replayed
 * through the same pipeline later. The format is an eight-byte magic number followed by
 * records of: receipt time in nanoseconds since the first frame (8 bytes), length (2 bytes)
 * and the datagram itself. All integers are big-endian.
 */
final class ArchiveWriter implements AutoCloseable {

    static final byte[] MAGIC = {'T', 'L', 'M', 'A', 'R', 'C', 'H', '1'};

    private final Path path;
    private final DataOutputStream out;
    private long origin = Long.MIN_VALUE;

    ArchiveWriter(Path path) throws IOException {
        this.path = path;
        Files.createDirectories(path.toAbsolutePath().getParent());
        this.out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path), 1 << 16));
        out.write(MAGIC);
    }

    Path path() {
        return path;
    }

    void write(RawFrame frame) throws IOException {
        if (origin == Long.MIN_VALUE) {
            origin = frame.receivedNanos();
        }
        out.writeLong(frame.receivedNanos() - origin);
        out.writeShort(frame.data().length);
        out.write(frame.data());
    }

    void flush() throws IOException {
        out.flush();
    }

    @Override
    public void close() throws IOException {
        out.close();
    }
}
