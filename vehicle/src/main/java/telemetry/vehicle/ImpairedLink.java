package telemetry.vehicle;

import java.util.SplittableRandom;

/**
 * Passes packets on after damaging some of them: dropped, one bit flipped, sent twice, or
 * held back and sent after the next one. Seeded, so a run can be repeated exactly.
 */
public final class ImpairedLink implements PacketSink {

    private final PacketSink downstream;
    private final LinkProfile profile;
    private final SplittableRandom random;
    private byte[] held;

    public ImpairedLink(PacketSink downstream, LinkProfile profile, long seed) {
        this.downstream = downstream;
        this.profile = profile;
        this.random = new SplittableRandom(seed);
    }

    @Override
    public void send(byte[] packet) {
        if (random.nextDouble() < profile.loss()) {
            return;
        }
        byte[] out = packet;
        if (random.nextDouble() < profile.corruption()) {
            out = packet.clone();
            int bit = random.nextInt(out.length * 8);
            out[bit / 8] ^= (byte) (1 << (bit % 8));
        }
        if (held == null && random.nextDouble() < profile.reordering()) {
            held = out;
            return;
        }
        downstream.send(out);
        if (random.nextDouble() < profile.duplication()) {
            downstream.send(out);
        }
        if (held != null) {
            downstream.send(held);
            held = null;
        }
    }
}
