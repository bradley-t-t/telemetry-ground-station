package telemetry.ground;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import telemetry.protocol.Dictionary;
import telemetry.vehicle.Scenario;

class ReplayTest {

    @TempDir
    Path directory;

    @Test
    void replayingAnArchiveReproducesTheLiveResult() throws IOException {
        Path archive = directory.resolve("flight.tlm");
        TelemetryProcessor live = new TelemetryProcessor(Dictionary.standard());
        try (ArchiveWriter writer = new ArchiveWriter(archive)) {
            FlightHarness.fly(Scenario.ENGINE_OUT, 5, live, frame -> {
                try {
                    writer.write(frame);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
        TelemetryProcessor replayed = Replay.process(archive, Dictionary.standard());

        String expected = FlightReport.render(live, "test");
        assertEquals(expected, FlightReport.render(replayed, "test"));
        assertTrue(expected.contains("Engine 5 lost chamber pressure"));
    }

    @Test
    void refusesAFileThatIsNotAnArchive() throws IOException {
        Path bogus = directory.resolve("bogus.tlm");
        Files.writeString(bogus, "not telemetry");
        assertThrows(IOException.class, () -> Replay.process(bogus, Dictionary.standard()));
    }
}
