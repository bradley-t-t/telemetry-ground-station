package telemetry.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import telemetry.ground.DemoControl;
import telemetry.ground.GroundStation;
import telemetry.vehicle.FlightRunner;
import telemetry.vehicle.FlightSimulation;
import telemetry.vehicle.Scenario;
import telemetry.vehicle.UdpSink;

/**
 * Runs simulated flights against the ground station over real UDP, one at a time. Starting
 * a new flight stops the one in progress and tells the station to begin a new record.
 */
final class DemoFlights implements DemoControl, AutoCloseable {

    private final GroundStation station;
    private final InetSocketAddress target;
    private final AtomicLong seeds = new AtomicLong(System.nanoTime());
    private Thread current;

    DemoFlights(GroundStation station, InetSocketAddress target) {
        this.station = station;
        this.target = target;
    }

    @Override
    public List<Option> scenarios() {
        return Arrays.stream(Scenario.values())
                .map(s -> new Option(s.id(), s.label(), s.description()))
                .toList();
    }

    @Override
    public synchronized void launch(String scenarioId, double speed) {
        Scenario scenario = Scenario.byId(scenarioId);
        stopCurrent();
        String label = scenario.label() + (speed == 1 ? "" : " at " + Main.formatSpeed(speed));
        station.startNewFlight(label);
        long seed = seeds.incrementAndGet();
        current = Thread.ofPlatform().name("vehicle-" + scenario.id()).daemon().start(() -> fly(scenario, speed, seed));
    }

    private void fly(Scenario scenario, double speed, long seed) {
        try (UdpSink sink = new UdpSink(target)) {
            FlightRunner.runPaced(new FlightSimulation(scenario, sink, seed), speed);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void stopCurrent() {
        if (current == null) {
            return;
        }
        current.interrupt();
        try {
            current.join(2_000);
            // Let datagrams already in flight drain before the station starts a new record.
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        current = null;
    }

    @Override
    public synchronized void close() {
        stopCurrent();
    }
}
