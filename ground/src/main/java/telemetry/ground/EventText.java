package telemetry.ground;

import java.util.Locale;
import telemetry.protocol.FlightEvent;

/** The short detail shown beside each event in the timeline and the report. */
public final class EventText {

    private EventText() {
    }

    public static String detail(FlightEvent event, double arg) {
        return switch (event) {
            case IGNITION, ENTRY_BURN_START -> String.format(Locale.ROOT, "%.0f engines", arg);
            case THROTTLE_DOWN, THROTTLE_UP -> String.format(Locale.ROOT, "%.0f%%", arg);
            case MAX_Q -> String.format(Locale.ROOT, "%.1f kPa", arg);
            case MECO -> String.format(Locale.ROOT, "%.1f t propellant left", arg / 1000);
            case FAIRING_SEPARATION -> String.format(Locale.ROOT, "%.0f km", arg);
            case ENTRY_BURN_END -> String.format(Locale.ROOT, "%.0f m/s", arg);
            case LANDING_BURN_START -> String.format(Locale.ROOT, "%.1f km", arg / 1000);
            case LANDING_LEGS -> String.format(Locale.ROOT, "%.0f m", arg);
            case TOUCHDOWN, BOOSTER_LOST -> String.format(Locale.ROOT, "%.1f m/s", arg);
            case SECO, ORBIT -> String.format(Locale.ROOT, "perigee %.1f km", arg);
            case PAYLOAD_SEPARATION -> String.format(Locale.ROOT, "%.1f t", arg / 1000);
            case ENGINE_SHUTDOWN -> String.format(Locale.ROOT, "engine %.0f", arg);
            case COUNTDOWN, LIFTOFF, STAGE_SEPARATION, S2_IGNITION, GRID_FINS, END_OF_MISSION -> "";
        };
    }
}
