package telemetry.ground;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import telemetry.protocol.Channel;
import telemetry.protocol.DecodeResult;
import telemetry.protocol.DecodedPacket;
import telemetry.protocol.Dictionary;
import telemetry.protocol.FlightEvent;
import telemetry.protocol.Rejected;
import telemetry.protocol.Severity;

/** Everything the ground station has learned about one flight. Owned by one thread. */
final class Flight {

    final int number;
    final Dictionary dictionary;
    final ChannelTable table;
    final LinkStatistics link = new LinkStatistics();
    final AlarmManager alarms = new AlarmManager();
    final EventLog events = new EventLog();
    final MissionClock clock = new MissionClock();
    final History history = new History();
    final LimitMonitor limits;
    final RateMonitor rates;
    final StuckSensorMonitor stuck;
    final StalenessMonitor staleness;
    /** APIDs whose source the vehicle has declared gone, so their silence is expected. */
    final Set<Integer> silenced = new HashSet<>();
    long lastFrame = ChannelTable.NEVER;
    /** Spacecraft clock of the first packet applied. */
    double firstClock = Double.NaN;
    boolean complete;

    private final List<Monitor> monitors;
    private final int eventApid;
    private final Channel eventCode;
    private final Channel eventTime;
    private final Channel eventArg;

    Flight(int number, Dictionary dictionary) {
        this.number = number;
        this.dictionary = dictionary;
        this.table = new ChannelTable(dictionary);
        this.limits = new LimitMonitor(this);
        this.rates = new RateMonitor(this);
        this.stuck = new StuckSensorMonitor(this);
        this.staleness = new StalenessMonitor(this);
        this.monitors = List.of(limits, rates, stuck, new EngineHealthMonitor(this), new DerivedEvents(this));
        this.eventApid = dictionary.packet("EVENTS").apid();
        this.eventCode = dictionary.channel("EVENT_CODE");
        this.eventTime = dictionary.channel("EVENT_TIME");
        this.eventArg = dictionary.channel("EVENT_ARG");
    }

    void accept(RawFrame frame, DecodeResult result) {
        long now = frame.receivedNanos();
        lastFrame = now;
        link.frame(now, frame.data().length);
        switch (result) {
            case Rejected rejected -> link.rejected(rejected);
            case DecodedPacket packet -> accept(packet, now);
        }
    }

    void tick(long now) {
        staleness.tick(now);
    }

    /** Whether a packet belongs to a new flight rather than this one. */
    boolean isStartOfNextFlight(DecodedPacket packet) {
        if (Double.isNaN(clock.latest())) {
            return false;
        }
        if (packet.clock() < clock.latest() - 30) {
            return true;
        }
        return complete && packet.apid() == eventApid
                && FlightEvent.ofCode((int) packet.value(eventCode)) == FlightEvent.COUNTDOWN;
    }

    private void accept(DecodedPacket packet, long now) {
        if (link.sequence(packet.apid(), packet.sequence()) == SequenceTracker.Outcome.DUPLICATE) {
            return;
        }
        boolean fresh = table.apply(packet, now);
        if (packet.apid() == eventApid) {
            onEvent(packet);
        }
        if (!fresh) {
            return;
        }
        clock.observe(packet.clock());
        if (Double.isNaN(firstClock)) {
            firstClock = packet.clock();
        }
        for (Monitor monitor : monitors) {
            monitor.onPacket(packet);
        }
        history.sample(clock, table.values());
    }

    private void onEvent(DecodedPacket packet) {
        FlightEvent event = FlightEvent.ofCode((int) packet.value(eventCode));
        if (event == null) {
            return;
        }
        double when = packet.value(eventTime);
        double arg = packet.value(eventArg);
        switch (event) {
            case COUNTDOWN -> clock.countdown(arg);
            case LIFTOFF -> clock.liftoff(when);
            default -> {
            }
        }
        if (!events.record(event, when, arg, false)) {
            return;
        }
        switch (event) {
            case BOOSTER_LOST -> {
                silenced.add(dictionary.packet("S1_NAV").apid());
                silenced.add(dictionary.packet("S1_PROP").apid());
                silenced.add(dictionary.packet("S1_AVIONICS").apid());
                alarms.raise("BOOSTER_LOST", Severity.WARNING, null,
                        "Booster hit the deck at " + Format.value(arg, "m/s") + " and was lost", when);
            }
            case END_OF_MISSION -> complete = true;
            default -> {
            }
        }
    }
}
