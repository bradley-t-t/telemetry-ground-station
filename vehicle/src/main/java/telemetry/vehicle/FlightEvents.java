package telemetry.vehicle;

import telemetry.protocol.FlightEvent;

/** Where the flight computers report discrete events. */
@FunctionalInterface
interface FlightEvents {

    void emit(FlightEvent event, double arg);
}
