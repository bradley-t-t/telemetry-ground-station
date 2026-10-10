package telemetry.ground;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Reads an archive written by {@link ArchiveWriter}, one frame at a time. */
public final class ArchiveReader implements AutoCloseable {

    private final DataInputStream in;

    public ArchiveReader(Path path) throws IOException {
        this.in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path), 1 << 16));
        byte[] magic = new byte[ArchiveWriter.MAGIC.length];
        try {
            in.readFully(magic);
        } catch (EOFException e) {
            magic = new byte[0];
        }
        if (!Arrays.equals(magic, ArchiveWriter.MAGIC)) {
            in.close();
            throw new IOException(path + " is not a telemetry archive");
        }
    }

    /** The next frame, or null at the end of the archive. */
    public RawFrame next() throws IOException {
        long nanos;
        try {
            nanos = in.readLong();
        } catch (EOFException e) {
            return null;
        }
        byte[] data = new byte[in.readUnsignedShort()];
        in.readFully(data);
        return new RawFrame(nanos, data);
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
