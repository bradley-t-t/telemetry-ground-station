package telemetry.ground;

import java.util.List;

/**
 * Lets the dashboard start simulated flights. The ground station knows nothing about the
 * simulator; the application wires one in when it runs the two together.
 */
public interface DemoControl {

    record Option(String id, String label, String description) {
    }

    List<Option> scenarios();

    void launch(String scenarioId, double speed);
}
