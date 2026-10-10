package telemetry.vehicle;

/**
 * Earth as a non-rotating sphere with a point-mass gravity field. The launch site sits at
 * (0, RADIUS) and downrange is clockwise, towards +x.
 */
final class Earth {

    static final double MU = 3.986004418e14;
    static final double RADIUS = 6_371_000.0;
    static final double G0 = 9.80665;

    private Earth() {
    }

    static Vec2 launchSite() {
        return new Vec2(0, RADIUS);
    }

    static double altitude(Vec2 position) {
        return position.norm() - RADIUS;
    }

    static Vec2 gravity(Vec2 position) {
        double r = position.norm();
        return position.times(-MU / (r * r * r));
    }
}
