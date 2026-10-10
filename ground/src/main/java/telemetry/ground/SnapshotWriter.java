package telemetry.ground;

import java.util.HashMap;
import java.util.Map;
import telemetry.protocol.Channel;
import telemetry.protocol.FlightEvent;
import telemetry.protocol.PacketDefinition;
import telemetry.protocol.Severity;

/** Renders the processor's state as the JSON the dashboard draws from. */
final class SnapshotWriter {

    static final int NO_DATA = 0;
    static final int NOMINAL = 1;
    static final int CAUTION = 2;
    static final int WARNING = 3;
    static final int STALE = 4;
    static final int SUSPECT = 5;

    private SnapshotWriter() {
    }

    record Station(String label, int queueDepth, long dropped, long now) {
    }

    static String write(TelemetryProcessor processor, Station station) {
        Flight flight = processor.flight();
        Json json = new Json(16 * 1024);
        json.beginObject();
        json.field("flight", flight.number);
        json.field("label", station.label());
        json.field("state", state(flight));
        json.name("met");
        if (flight.clock.known() && !Double.isNaN(flight.clock.latest())) {
            json.value(flight.clock.met());
        } else {
            json.nullValue();
        }
        writeValues(json, flight);
        writeAlarms(json, flight);
        writeEvents(json, flight);
        writeLink(json, flight, station);
        json.endObject();
        return json.toString();
    }

    static String state(Flight flight) {
        if (flight.lastFrame == ChannelTable.NEVER) {
            return "IDLE";
        }
        if (flight.complete) {
            return "COMPLETE";
        }
        return flight.staleness.lossOfSignal() ? "LOS" : "LIVE";
    }

    private static void writeValues(Json json, Flight flight) {
        Map<String, Severity> alarmed = new HashMap<>();
        for (Alarm alarm : flight.alarms.current()) {
            if (alarm.channel != null && alarm.state == Alarm.State.ACTIVE) {
                alarmed.merge(alarm.channel, alarm.severity, (a, b) -> a.compareTo(b) >= 0 ? a : b);
            }
        }
        double[] values = flight.table.values();
        json.name("values").beginArray();
        for (double value : values) {
            json.value(value);
        }
        json.endArray();
        json.name("status").beginArray();
        for (Channel channel : flight.dictionary.channels()) {
            json.value(status(flight, channel, values, alarmed));
        }
        json.endArray();
    }

    private static int status(Flight flight, Channel channel, double[] values, Map<String, Severity> alarmed) {
        int i = channel.index();
        if (Double.isNaN(values[i])) {
            return NO_DATA;
        }
        if (flight.staleness.stale(channel.apid()) && !flight.complete) {
            return STALE;
        }
        if (flight.stuck.suspect(i)) {
            return SUSPECT;
        }
        Severity severity = flight.limits.severity(i);
        Severity fromAlarm = alarmed.get(channel.name());
        if (fromAlarm != null && fromAlarm.compareTo(severity) > 0) {
            severity = fromAlarm;
        }
        return switch (severity) {
            case NOMINAL -> NOMINAL;
            case CAUTION -> CAUTION;
            case WARNING -> WARNING;
        };
    }

    private static void writeAlarms(Json json, Flight flight) {
        json.name("alarms").beginArray();
        for (Alarm alarm : flight.alarms.current()) {
            json.beginObject()
                    .field("id", alarm.id)
                    .field("key", alarm.key)
                    .field("severity", alarm.severity.name())
                    .field("state", alarm.state.name())
                    .field("acknowledged", alarm.acknowledged)
                    .field("channel", alarm.channel)
                    .field("message", alarm.message)
                    .name("met").value(flight.clock.known() ? flight.clock.metAt(alarm.raisedClock) : Double.NaN)
                    .endObject();
        }
        json.endArray();
    }

    private static void writeEvents(Json json, Flight flight) {
        json.name("events").beginArray();
        for (EventLog.Entry entry : flight.events.entries()) {
            FlightEvent event = entry.event();
            json.beginObject()
                    .field("name", event.name())
                    .field("label", event.label())
                    .name("met").value(flight.clock.known() ? flight.clock.metAt(entry.clock()) : Double.NaN)
                    .field("arg", entry.arg())
                    .field("ground", entry.ground())
                    .endObject();
        }
        json.endArray();
    }

    private static void writeLink(Json json, Flight flight, Station station) {
        LinkStatistics link = flight.link;
        LatencyHistogram latency = link.latency();
        long now = station.now();
        json.name("link").beginObject()
                .field("frames", link.frames())
                .field("received", link.received())
                .field("rate", link.packetsPerSecond(now))
                .field("byteRate", link.bytesPerSecond(now))
                .field("lost", link.lost())
                .field("lossPercent", link.lossPercent())
                .field("crcErrors", link.crcErrors())
                .field("malformed", link.malformed())
                .field("unknownApid", link.unknownApid())
                .field("duplicates", link.duplicates())
                .field("late", link.late())
                .field("queue", station.queueDepth())
                .field("dropped", station.dropped())
                .field("latencyP50", latency.percentileMicros(0.5))
                .field("latencyP99", latency.percentileMicros(0.99));
        json.name("packets").beginArray();
        for (PacketDefinition packet : flight.dictionary.packets()) {
            SequenceTracker tracker = link.tracker(packet.apid());
            long last = flight.table.lastReceived(packet.apid());
            json.beginObject()
                    .field("apid", packet.apid())
                    .field("name", packet.name())
                    .field("rate", packet.rateHz())
                    .field("received", tracker == null ? 0 : tracker.received())
                    .field("lost", tracker == null ? 0 : tracker.lost())
                    .name("age").value(last == ChannelTable.NEVER ? Double.NaN : (now - last) / 1e9)
                    .field("stale", flight.staleness.stale(packet.apid()))
                    .endObject();
        }
        json.endArray();
        json.endObject();
    }
}
