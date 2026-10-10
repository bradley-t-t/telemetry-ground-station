package telemetry.ground;

import telemetry.protocol.DecodedPacket;

/** A check that runs on every packet applied to the channel table, in arrival order. */
interface Monitor {

    void onPacket(DecodedPacket packet);
}
