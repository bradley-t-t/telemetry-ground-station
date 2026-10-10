package telemetry.vehicle;

/** A vector in the plane of the trajectory, Earth-centred and inertial. */
record Vec2(double x, double y) {

    static final Vec2 ZERO = new Vec2(0, 0);

    Vec2 plus(Vec2 other) {
        return new Vec2(x + other.x, y + other.y);
    }

    Vec2 minus(Vec2 other) {
        return new Vec2(x - other.x, y - other.y);
    }

    Vec2 times(double k) {
        return new Vec2(x * k, y * k);
    }

    double dot(Vec2 other) {
        return x * other.x + y * other.y;
    }

    double norm() {
        return Math.sqrt(x * x + y * y);
    }

    Vec2 unit() {
        double n = norm();
        return n == 0 ? ZERO : times(1 / n);
    }
}
