package telemetry.ground;

import java.util.Locale;
import telemetry.protocol.Dictionary;
import telemetry.protocol.FlightEvent;

/** A plain-text summary of one flight: link quality, timeline, alarms and outcome. */
public final class FlightReport {

    private FlightReport() {
    }

    public static String render(TelemetryProcessor processor, String title) {
        Flight flight = processor.flight();
        Dictionary dictionary = flight.dictionary;
        LinkStatistics link = flight.link;
        StringBuilder out = new StringBuilder(4096);

        out.append("Flight report");
        if (title != null && !title.isEmpty()) {
            out.append("  ").append(title);
        }
        out.append("\n\n");

        out.append("Telemetry\n");
        if (flight.clock.known()) {
            line(out, "%,d frames from %s to %s", link.frames(),
                    Format.met(flight.clock.metAt(flight.firstClock)), Format.met(flight.clock.met()));
        } else {
            line(out, "%,d frames, none of them carrying a countdown", link.frames());
        }
        line(out, "Lost %,d (%.2f%%)   CRC errors %,d   Malformed %,d   Duplicates %,d   Late %,d",
                link.lost(), link.lossPercent(), link.crcErrors(), link.malformed(), link.duplicates(), link.late());
        out.append('\n');

        out.append("Timeline\n");
        for (EventLog.Entry entry : flight.events.entries()) {
            String detail = EventText.detail(entry.event(), entry.arg());
            if (entry.ground()) {
                detail = detail.isEmpty() ? "seen by ground" : detail + ", seen by ground";
            }
            line(out, "%-10s %-22s %s", Format.met(flight.clock.metAt(entry.clock())), entry.event().label(), detail);
        }
        out.append('\n');

        out.append("Alarms\n");
        int alarms = 0;
        for (AlarmManager.LogEntry entry : flight.alarms.log()) {
            if (entry.change() == AlarmManager.Change.RAISED || entry.change() == AlarmManager.Change.ESCALATED) {
                line(out, "%-10s %-8s %s", Format.met(flight.clock.metAt(entry.clock())), entry.severity(), entry.message());
                alarms++;
            }
        }
        if (alarms == 0) {
            line(out, "None");
        }
        out.append('\n');

        out.append("Outcome\n");
        double apogee = flight.table.value(dictionary.channel("S2_APOGEE"));
        double perigee = flight.table.value(dictionary.channel("S2_PERIGEE"));
        if (flight.events.contains(FlightEvent.SECO) && perigee > 0) {
            line(out, "%-10s orbit %.0f x %.0f km", "Stage 2", perigee, apogee);
        } else {
            line(out, "%-10s did not reach orbit", "Stage 2");
        }
        EventLog.Entry touchdown = flight.events.first(FlightEvent.TOUCHDOWN);
        EventLog.Entry lost = flight.events.first(FlightEvent.BOOSTER_LOST);
        double downrange = flight.table.value(dictionary.channel("S1_DOWNRANGE"));
        if (touchdown != null) {
            line(out, "%-10s landed at %.1f m/s, %.0f km downrange", "Stage 1", touchdown.arg(), downrange);
        } else if (lost != null) {
            line(out, "%-10s lost on landing at %.1f m/s", "Stage 1", lost.arg());
        } else {
            line(out, "%-10s no landing reported", "Stage 1");
        }
        EventLog.Entry maxQ = flight.events.first(FlightEvent.MAX_Q);
        if (maxQ != null) {
            line(out, "%-10s %.1f kPa at %s", "Max-Q", maxQ.arg(), Format.met(flight.clock.metAt(maxQ.clock())));
        }
        double stage1 = flight.history.max(dictionary.channel("S1_ACCEL").index(), dictionary.channel("S1_MODE").index(), 1);
        double stage2 = flight.history.max(dictionary.channel("S2_ACCEL").index(), dictionary.channel("S2_MODE").index(), 1);
        line(out, "%-10s %.2f g on stage 1 ascent, %.2f g on stage 2", "Peak load", stage1, stage2);
        return out.toString();
    }

    private static void line(StringBuilder out, String format, Object... args) {
        out.append("  ").append(String.format(Locale.ROOT, format, args).stripTrailing()).append('\n');
    }
}
