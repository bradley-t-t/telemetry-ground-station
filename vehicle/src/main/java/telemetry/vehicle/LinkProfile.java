package telemetry.vehicle;

/** Impairments applied to the downlink, each as a probability per packet. */
public record LinkProfile(double loss, double corruption, double duplication, double reordering) {

    public static final LinkProfile CLEAN = new LinkProfile(0, 0, 0, 0);

    public boolean isClean() {
        return loss == 0 && corruption == 0 && duplication == 0 && reordering == 0;
    }
}
