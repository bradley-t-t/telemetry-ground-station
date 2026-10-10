package telemetry.protocol;

import java.util.Locale;

/** What kind of measurement a channel carries, which decides the checks that apply to it. */
public enum ChannelClass {
    /** A transducer reading. Real sensors are noisy, so an unchanging value means a fault. */
    SENSOR,
    /** A value the flight computer calculates. */
    DERIVED,
    /** A discrete state such as a mode or a flag. */
    STATUS;

    static ChannelClass parse(String token) {
        return valueOf(token.toUpperCase(Locale.ROOT));
    }
}
