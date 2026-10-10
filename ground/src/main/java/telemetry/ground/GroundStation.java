package telemetry.ground;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import telemetry.protocol.Dictionary;

/**
 * The live ground station. A receiver thread feeds a bounded queue; one processor thread
 * owns all flight state and is the only thing that ever touches it. Other threads talk to
 * it by queueing commands, and read it through immutable snapshots published ten times a
 * second, so there are no locks around the telemetry itself.
 */
public final class GroundStation implements AutoCloseable {

    private static final int QUEUE_CAPACITY = 65_536;
    private static final long TICK_NANOS = 50_000_000L;
    private static final long PUBLISH_NANOS = 100_000_000L;
    private static final int BATCH = 512;
    private static final DateTimeFormatter ARCHIVE_NAME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final TelemetryProcessor processor;
    private final BlockingQueue<RawFrame> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final Queue<Runnable> commands = new ConcurrentLinkedQueue<>();
    private final SnapshotFeed feed = new SnapshotFeed();
    private final AtomicLong dropped = new AtomicLong();
    private final InetSocketAddress udpAddress;
    private final Path archiveDirectory;
    private final Thread worker;
    private UdpReceiver receiver;
    private ArchiveWriter archive;
    private int archivedFlight;
    private volatile boolean running = true;
    private volatile String label = "";
    private long version;

    /**
     * @param udpAddress       where to listen for telemetry, or null to take frames only from
     *                         {@link #inject(byte[])}
     * @param archiveDirectory where to record each flight, or null for no recording
     */
    public GroundStation(Dictionary dictionary, InetSocketAddress udpAddress, Path archiveDirectory) {
        this.processor = new TelemetryProcessor(dictionary);
        this.udpAddress = udpAddress;
        this.archiveDirectory = archiveDirectory;
        this.worker = Thread.ofPlatform().name("telemetry-processor").unstarted(this::run);
    }

    public void start() throws IOException {
        if (udpAddress != null) {
            receiver = new UdpReceiver(udpAddress, this::enqueue);
            receiver.start();
        }
        publish(System.nanoTime());
        worker.start();
    }

    /** The bound UDP address, which tells you the port when 0 was asked for. */
    public InetSocketAddress udpAddress() throws IOException {
        return receiver == null ? null : receiver.localAddress();
    }

    public Dictionary dictionary() {
        return processor.dictionary();
    }

    public SnapshotFeed feed() {
        return feed;
    }

    public void setLabel(String label) {
        this.label = label == null ? "" : label;
    }

    /** Queues a packet as though it had just arrived over the network. */
    public void inject(byte[] packet) {
        enqueue(new RawFrame(System.nanoTime(), packet));
    }

    /** Throws away anything queued and starts a new flight. Returns once that has happened. */
    public void startNewFlight(String label) {
        call(processor -> {
            queue.clear();
            processor.startNextFlight();
            this.label = label == null ? "" : label;
            return null;
        });
    }

    public void acknowledge(int alarmId) {
        commands.add(() -> processor.acknowledge(alarmId));
    }

    public void acknowledgeAll() {
        commands.add(processor::acknowledgeAll);
    }

    /** The post-flight report for the current flight, built on the processor thread. */
    public String report() {
        return call(processor -> FlightReport.render(processor, label));
    }

    /** Runs a function against the processor on its own thread and waits for the answer. */
    private <T> T call(Function<TelemetryProcessor, T> function) {
        CompletableFuture<T> result = new CompletableFuture<>();
        commands.add(() -> {
            try {
                result.complete(function.apply(processor));
            } catch (RuntimeException e) {
                result.completeExceptionally(e);
            }
        });
        try {
            return result.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("The processor did not answer", e);
        }
    }

    private void enqueue(RawFrame frame) {
        if (!queue.offer(frame)) {
            dropped.incrementAndGet();
        }
    }

    private void run() {
        long lastTick = System.nanoTime();
        long lastPublish = lastTick;
        while (running) {
            try {
                RawFrame frame = queue.poll(20, TimeUnit.MILLISECONDS);
                for (int i = 0; frame != null && i < BATCH; i++) {
                    handle(frame);
                    frame = i < BATCH - 1 ? queue.poll() : null;
                }
            } catch (InterruptedException e) {
                return;
            }
            Runnable command;
            while ((command = commands.poll()) != null) {
                command.run();
            }
            long now = System.nanoTime();
            if (now - lastTick >= TICK_NANOS) {
                processor.tick(now);
                lastTick = now;
            }
            if (now - lastPublish >= PUBLISH_NANOS) {
                publish(now);
                flushArchive();
                lastPublish = now;
            }
        }
    }

    private void handle(RawFrame frame) {
        processor.process(frame);
        processor.recordLatency(System.nanoTime() - frame.receivedNanos());
        record(frame);
    }

    private void publish(long now) {
        String json = SnapshotWriter.write(processor,
                new SnapshotWriter.Station(label, queue.size(), dropped.get(), now));
        feed.publish(new Snapshot(++version, processor.flightNumber(), json, processor.history()));
    }

    private void record(RawFrame frame) {
        if (archiveDirectory == null) {
            return;
        }
        try {
            if (archive == null || archivedFlight != processor.flightNumber()) {
                if (archive != null) {
                    archive.close();
                }
                String name = "flight-" + LocalDateTime.now().format(ARCHIVE_NAME) + "-" + processor.flightNumber() + ".tlm";
                archive = new ArchiveWriter(archiveDirectory.resolve(name));
                archivedFlight = processor.flightNumber();
            }
            archive.write(frame);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the flight archive", e);
        }
    }

    private void flushArchive() {
        if (archive == null) {
            return;
        }
        try {
            archive.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the flight archive", e);
        }
    }

    /** Where the current flight is being recorded, or null. */
    public Path archivePath() {
        return call(processor -> archive == null ? null : archive.path());
    }

    @Override
    public void close() throws IOException {
        if (receiver != null) {
            receiver.close();
        }
        running = false;
        try {
            worker.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (archive != null) {
            archive.close();
        }
    }
}
