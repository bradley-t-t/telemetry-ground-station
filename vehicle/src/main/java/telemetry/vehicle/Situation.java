package telemetry.vehicle;

/**
 * Everything a flight computer knows about its stage at one instant.
 *
 * @param met                mission elapsed time, seconds from liftoff
 * @param mass               mass being flown, which includes the upper stage while stacked
 * @param dynamicPressure    in pascals
 * @param ambientPressure    in pascals
 * @param aeroAcceleration   drag divided by mass, in m/s^2
 * @param gravity            local gravitational acceleration, in m/s^2
 */
record Situation(double met, Nav nav, double mass, double dynamicPressure, double mach,
                 double ambientPressure, Vec2 aeroAcceleration, double gravity) {
}
