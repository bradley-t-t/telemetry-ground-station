package telemetry.vehicle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AtmosphereTest {

    @Test
    void seaLevelMatchesTheStandard() {
        Atmosphere.Conditions air = Atmosphere.at(0);
        assertEquals(101_325, air.pressure(), 1);
        assertEquals(1.225, air.density(), 0.001);
        assertEquals(288.15, air.temperature(), 0.01);
        assertEquals(340.3, air.speedOfSound(), 0.1);
    }

    @Test
    void tropopauseMatchesTheStandard() {
        // 11 km geopotential is 11,019 m geometric.
        Atmosphere.Conditions air = Atmosphere.at(11_019);
        assertEquals(216.65, air.temperature(), 0.05);
        assertEquals(22_632, air.pressure(), 5);
    }

    @Test
    void densityFallsMonotonically() {
        double previous = Double.MAX_VALUE;
        for (int altitude = 0; altitude <= 300_000; altitude += 500) {
            double density = Atmosphere.at(altitude).density();
            assertTrue(density < previous, "density rose at " + altitude + " m");
            previous = density;
        }
    }
}
