package telemetry.vehicle;

/**
 * A navigation solution: position and velocity resolved into the local vertical and
 * horizontal, plus the orbit they describe. Distances in metres, angles in degrees.
 */
record Nav(double altitude, double downrange, double speed, double verticalSpeed, double horizontalSpeed,
           double flightPathAngle, double apogee, double perigee, Vec2 up, Vec2 east) {

    static Nav of(Vec2 position, Vec2 velocity) {
        double radius = position.norm();
        Vec2 up = position.times(1 / radius);
        Vec2 east = new Vec2(up.y(), -up.x());
        double vertical = velocity.dot(up);
        double horizontal = velocity.dot(east);
        double speed = velocity.norm();
        double flightPath = speed < 1 ? 90 : Math.toDegrees(Math.atan2(vertical, horizontal));
        double downrange = Earth.RADIUS * Math.atan2(position.x(), position.y());

        double energy = speed * speed / 2 - Earth.MU / radius;
        double momentum = position.x() * velocity.y() - position.y() * velocity.x();
        double apogee = Double.NaN;
        double perigee = Double.NaN;
        if (energy < 0) {
            double semiMajor = -Earth.MU / (2 * energy);
            double e = Math.sqrt(Math.max(0, 1 + 2 * energy * momentum * momentum / (Earth.MU * Earth.MU)));
            apogee = semiMajor * (1 + e) - Earth.RADIUS;
            perigee = semiMajor * (1 - e) - Earth.RADIUS;
        }
        return new Nav(radius - Earth.RADIUS, downrange, speed, vertical, horizontal, flightPath,
                apogee, perigee, up, east);
    }

    /** A unit vector {@code pitch} degrees above the local horizontal, pointing downrange. */
    Vec2 direction(double pitch) {
        double radians = Math.toRadians(pitch);
        return east.times(Math.cos(radians)).plus(up.times(Math.sin(radians)));
    }
}
