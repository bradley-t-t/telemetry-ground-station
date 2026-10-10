package telemetry.ground;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import telemetry.protocol.Dictionary;
import telemetry.vehicle.FlightSimulation;
import telemetry.vehicle.PacketSink;
import telemetry.vehicle.Scenario;

/**
 * Flies a scenario straight into a processor with no network in between. Each packet is
 * stamped with the simulation time it was sent, and the processor ticks every 50 ms of
 * simulated time, exactly as the live station does against the wall clock.
 */
final class FlightHarness {

    private static final long TICK_NANOS = 50_000_000L;

    private FlightHarness() {
    }

    static TelemetryProcessor fly(Scenario scenario, long seed) {
        TelemetryProcessor processor = new TelemetryProcessor(Dictionary.standard());
        fly(scenario, seed, processor, frame -> { });
        return processor;
    }

    static void fly(Scenario scenario, long seed, TelemetryProcessor processor, Consumer<RawFrame> tap) {
        AtomicReference<FlightSimulation> flight = new AtomicReference<>();
        PacketSink sink = packet -> {
            RawFrame frame = new RawFrame(nanos(flight.get().time()), packet);
            tap.accept(frame);
            processor.process(frame);
        };
        flight.set(new FlightSimulation(scenario, sink, seed));
        long nextTick = 0;
        while (!flight.get().finished()) {
            flight.get().step();
            long now = nanos(flight.get().time());
            if (now >= nextTick) {
                processor.tick(now);
                nextTick = now + TICK_NANOS;
            }
        }
        long end = nanos(flight.get().time());
        processor.tick(end + 5_000_000_000L);
    }

    static long nanos(double seconds) {
        return (long) (seconds * 1e9);
    }
}
