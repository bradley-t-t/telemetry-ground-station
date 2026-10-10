package telemetry.ground;

import telemetry.protocol.DecodeResult;
import telemetry.protocol.DecodedPacket;
import telemetry.protocol.Dictionary;
import telemetry.protocol.PacketDecoder;

/**
 * Turns raw frames into flight state: decode, sequence accounting, the channel table, then
 * every monitor in a fixed order. It is single-threaded and takes time only from the frames
 * and ticks it is given, so replaying an archive reproduces a live session exactly.
 */
public final class TelemetryProcessor {

    private final Dictionary dictionary;
    private final PacketDecoder decoder;
    private Flight flight;

    public TelemetryProcessor(Dictionary dictionary) {
        this.dictionary = dictionary;
        this.decoder = new PacketDecoder(dictionary);
        this.flight = new Flight(1, dictionary);
    }

    public void process(RawFrame frame) {
        DecodeResult result = decoder.decode(frame.data());
        if (result instanceof DecodedPacket packet && flight.isStartOfNextFlight(packet)) {
            startNextFlight();
        }
        flight.accept(frame, result);
    }

    public void tick(long nanos) {
        flight.tick(nanos);
    }

    public void startNextFlight() {
        flight = new Flight(flight.number + 1, dictionary);
    }

    public boolean acknowledge(int alarmId) {
        return flight.alarms.acknowledge(alarmId, flight.clock.latest());
    }

    public void acknowledgeAll() {
        flight.alarms.acknowledgeAll(flight.clock.latest());
    }

    public int flightNumber() {
        return flight.number;
    }

    public Dictionary dictionary() {
        return dictionary;
    }

    public History history() {
        return flight.history;
    }

    Flight flight() {
        return flight;
    }

    void recordLatency(long nanos) {
        flight.link.recordLatency(nanos);
    }
}
