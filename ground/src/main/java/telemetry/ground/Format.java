package telemetry.ground;

import java.util.Locale;

/** Number and time formatting for alarm messages, reports and the console. */
public final class Format {

    private Format() {
    }

    public static String value(double value, String units) {
        String number = number(value);
        return units.isEmpty() ? number : number + " " + units;
    }

    /** One more decimal place than {@link #value}, for telling two close numbers apart. */
    static String precise(double value, String units) {
        double magnitude = Math.abs(value);
        String pattern = magnitude >= 100 ? "%.1f" : magnitude >= 10 ? "%.2f" : "%.3f";
        String number = String.format(Locale.ROOT, pattern, value);
        return units.isEmpty() ? number : number + " " + units;
    }

    static String number(double value) {
        double magnitude = Math.abs(value);
        String pattern = magnitude >= 100 ? "%.0f" : magnitude >= 10 ? "%.1f" : "%.2f";
        return String.format(Locale.ROOT, pattern, value);
    }

    /** Mission elapsed time as T+mm:ss.s or T-mm:ss.s. */
    public static String met(double seconds) {
        if (Double.isNaN(seconds)) {
            return "T  --:--.-";
        }
        char sign = seconds < 0 ? '-' : '+';
        double magnitude = Math.abs(seconds);
        int minutes = (int) (magnitude / 60);
        double rest = magnitude - minutes * 60;
        return String.format(Locale.ROOT, "T%c%02d:%04.1f", sign, minutes, rest);
    }
}
