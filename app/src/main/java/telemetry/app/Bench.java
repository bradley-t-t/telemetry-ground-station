package telemetry.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import telemetry.ground.RawFrame;
import telemetry.ground.TelemetryProcessor;
import telemetry.protocol.DecodedPacket;
import telemetry.protocol.Dictionary;
import telemetry.protocol.PacketDecoder;
import telemetry.vehicle.FlightRunner;
import telemetry.vehicle.FlightSimulation;
import telemetry.vehicle.Scenario;

/**
 * Measures processing throughput: one full flight's packets, decoded and then run through
 * the whole pipeline, repeated until the JIT has settled. No network is involved, so this is
 * the ceiling the processor thread sets, not what a socket can deliver.
 */
final class Bench {

    private Bench() {
    }

    static void run(int rounds) {
        List<byte[]> packets = new ArrayList<>();
        FlightSimulation flight = new FlightSimulation(Scenario.NOISY_LINK, packets::add, 1);
        FlightRunner.runToCompletion(flight);
        System.out.printf(Locale.ROOT, "One flight is %,d packets over %.0f s of mission time.%n%n",
                packets.size(), flight.time());

        Dictionary dictionary = Dictionary.standard();
        PacketDecoder decoder = new PacketDecoder(dictionary);
        long[] times = new long[packets.size()];
        for (int i = 0; i < times.length; i++) {
            times[i] = (long) (i * (flight.time() / times.length) * 1e9);
        }

        double bestDecode = 0;
        double bestPipeline = 0;
        for (int round = 1; round <= rounds; round++) {
            long start = System.nanoTime();
            int accepted = 0;
            for (byte[] packet : packets) {
                if (decoder.decode(packet) instanceof DecodedPacket) {
                    accepted++;
                }
            }
            double decodeRate = packets.size() / ((System.nanoTime() - start) / 1e9);

            TelemetryProcessor processor = new TelemetryProcessor(dictionary);
            start = System.nanoTime();
            long nextTick = 0;
            for (int i = 0; i < times.length; i++) {
                if (times[i] >= nextTick) {
                    processor.tick(times[i]);
                    nextTick = times[i] + 50_000_000L;
                }
                processor.process(new RawFrame(times[i], packets.get(i)));
            }
            double pipelineRate = packets.size() / ((System.nanoTime() - start) / 1e9);

            bestDecode = Math.max(bestDecode, decodeRate);
            bestPipeline = Math.max(bestPipeline, pipelineRate);
            System.out.printf(Locale.ROOT, "round %2d   decode %,12.0f packets/s   full pipeline %,12.0f packets/s   (%,d valid)%n",
                    round, decodeRate, pipelineRate, accepted);
        }
        System.out.printf(Locale.ROOT, "%nBest: decode %,.0f packets/s, full pipeline %,.0f packets/s, %.2f us per packet.%n",
                bestDecode, bestPipeline, 1e6 / bestPipeline);
        System.out.printf(Locale.ROOT, "A live flight sends about %.0f packets/s.%n", packets.size() / flight.time());
    }
}
