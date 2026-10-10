package telemetry.app;

import java.awt.Desktop;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;
import telemetry.ground.DashboardServer;
import telemetry.ground.EventText;
import telemetry.ground.FlightReport;
import telemetry.ground.Format;
import telemetry.ground.GroundStation;
import telemetry.ground.Replay;
import telemetry.ground.TelemetryProcessor;
import telemetry.protocol.Dictionary;
import telemetry.vehicle.Fault;
import telemetry.vehicle.FlightRunner;
import telemetry.vehicle.FlightSimulation;
import telemetry.vehicle.ImpairedLink;
import telemetry.vehicle.LinkProfile;
import telemetry.vehicle.PacketSink;
import telemetry.vehicle.Scenario;
import telemetry.vehicle.UdpSink;

/** Command-line entry point. */
public final class Main {

    private static final String USAGE = """
            Usage: groundstation <command> [options]

            Commands
              demo      The ground station, its dashboard and a simulated flight, together
                          --scenario <id>    flight to start with (default nominal)
                          --speed <x>        time warp; 1 is real time (default 1)
                          --http <port>      dashboard port (default 8080)
                          --udp <port>       telemetry port (default 5600)
                          --archive <dir>    where flights are recorded (default flights)
                          --open             open the dashboard in a browser
              ground    Listen for telemetry and serve the dashboard
                          --udp <port>  --http <port>  --bind <address>  --archive <dir>  --open
              sim       Fly one mission and send its telemetry over UDP
                          --host <address>  --port <port>  --scenario <id>  --speed <x>  --seed <n>
                          --fault <spec>     add a fault, e.g. engine-out:5@80 (repeatable)
                          --loss <p>  --corrupt <p>  --duplicate <p>  --reorder <p>
              replay    Play a recorded flight back into the dashboard
                          <archive>  --speed <x>  --http <port>  --open
              report    Print the post-flight report for a recorded flight
                          <archive>
              bench     Measure how fast the pipeline processes packets
                          --rounds <n>

            Scenarios
            %s
            Faults
              engine-out:<engine>@<s>   engine-degraded:<engine>@<s>
              helium-leak:<stage>@<s>   stuck-sensor:<channel>@<s>
            """;

    private Main() {
    }

    public static void main(String[] args) {
        if (args.length == 0 || Set.of("help", "-h", "--help").contains(args[0])) {
            System.out.print(usage());
            return;
        }
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        try {
            switch (args[0]) {
                case "demo" -> demo(Options.parse(rest, Set.of("scenario", "speed", "http", "udp", "archive", "bind"), Set.of("open")));
                case "ground" -> ground(Options.parse(rest, Set.of("udp", "http", "bind", "archive"), Set.of("open")));
                case "sim" -> sim(Options.parse(rest, Set.of("host", "port", "scenario", "speed", "seed", "fault",
                        "loss", "corrupt", "duplicate", "reorder"), Set.of()));
                case "replay" -> replay(Options.parse(rest, Set.of("speed", "http", "bind"), Set.of("open")));
                case "report" -> report(Options.parse(rest, Set.of(), Set.of()));
                case "bench" -> Bench.run(Options.parse(rest, Set.of("rounds"), Set.of()).getInt("rounds", 8));
                default -> throw new IllegalArgumentException("Unknown command '" + args[0] + "'");
            }
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println("Run 'groundstation help' for usage.");
            System.exit(2);
        } catch (IOException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void demo(Options options) throws IOException, InterruptedException {
        String bind = options.get("bind", "127.0.0.1");
        Scenario scenario = Scenario.byId(options.get("scenario", "nominal"));
        double speed = options.getDouble("speed", 1);
        GroundStation station = new GroundStation(Dictionary.standard(),
                new InetSocketAddress(bind, options.getInt("udp", 5600)), Path.of(options.get("archive", "flights")));
        station.start();
        DemoFlights flights = new DemoFlights(station, new InetSocketAddress(bind, station.udpAddress().getPort()));
        DashboardServer dashboard = new DashboardServer(new InetSocketAddress(bind, options.getInt("http", 8080)), station, flights);
        dashboard.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> closeAll(flights, dashboard, station)));

        String url = url(dashboard);
        System.out.println("Ground station listening for telemetry on udp://" + bind + ":" + station.udpAddress().getPort());
        System.out.println("Dashboard at " + url);
        System.out.println("Flying " + scenario.label() + " at " + formatSpeed(speed) + ". Start other flights from the dashboard. Ctrl-C quits.");
        flights.launch(scenario.id(), speed);
        open(options, url);
        new CountDownLatch(1).await();
    }

    private static void ground(Options options) throws IOException, InterruptedException {
        String bind = options.get("bind", "127.0.0.1");
        GroundStation station = new GroundStation(Dictionary.standard(),
                new InetSocketAddress(bind, options.getInt("udp", 5600)), Path.of(options.get("archive", "flights")));
        station.start();
        DashboardServer dashboard = new DashboardServer(new InetSocketAddress(bind, options.getInt("http", 8080)), station, null);
        dashboard.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> closeAll(dashboard, station)));
        System.out.println("Ground station listening for telemetry on udp://" + bind + ":" + station.udpAddress().getPort());
        System.out.println("Dashboard at " + url(dashboard));
        open(options, url(dashboard));
        new CountDownLatch(1).await();
    }

    private static void sim(Options options) throws IOException {
        Scenario scenario = Scenario.byId(options.get("scenario", "nominal"));
        List<Fault> faults = new ArrayList<>(scenario.faults());
        for (String spec : options.all("fault")) {
            faults.add(Fault.parse(spec));
        }
        LinkProfile link = scenario.link();
        if (options.flag("loss") || options.flag("corrupt") || options.flag("duplicate") || options.flag("reorder")) {
            link = new LinkProfile(options.getDouble("loss", 0), options.getDouble("corrupt", 0),
                    options.getDouble("duplicate", 0), options.getDouble("reorder", 0));
        }
        long seed = Long.parseLong(options.get("seed", Long.toString(System.nanoTime())));
        double speed = options.getDouble("speed", 1);
        InetSocketAddress target = new InetSocketAddress(options.get("host", "127.0.0.1"), options.getInt("port", 5600));

        try (UdpSink udp = new UdpSink(target)) {
            PacketSink sink = link.isClean() ? udp : new ImpairedLink(udp, link, seed);
            FlightSimulation flight = new FlightSimulation(faults, sink, seed);
            flight.addListener(event -> System.out.printf(Locale.ROOT, "%-10s %-22s %s%n",
                    Format.met(event.met()), event.event().label(), EventText.detail(event.event(), event.arg())));
            System.out.printf(Locale.ROOT, "Flying %s to udp://%s:%d at %s, seed %d%n", scenario.label(),
                    target.getHostString(), target.getPort(), formatSpeed(speed), seed);
            for (Fault fault : faults) {
                System.out.println("Fault: " + fault.describe());
            }
            FlightRunner.runPaced(flight, speed);
        }
    }

    private static void replay(Options options) throws IOException, InterruptedException {
        Path archive = archiveArgument(options);
        String bind = options.get("bind", "127.0.0.1");
        GroundStation station = new GroundStation(Dictionary.standard(), null, null);
        station.setLabel("Replay of " + archive.getFileName());
        station.start();
        DashboardServer dashboard = new DashboardServer(new InetSocketAddress(bind, options.getInt("http", 8080)), station, null);
        dashboard.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> closeAll(dashboard, station)));
        System.out.println("Dashboard at " + url(dashboard));
        open(options, url(dashboard));
        Replay.feed(archive, station, options.getDouble("speed", 1));
        System.out.println("Replay finished. The dashboard stays up until Ctrl-C.");
        new CountDownLatch(1).await();
    }

    private static void report(Options options) throws IOException {
        Path archive = archiveArgument(options);
        TelemetryProcessor processor = Replay.process(archive, Dictionary.standard());
        System.out.print(FlightReport.render(processor, archive.getFileName().toString()));
    }

    private static Path archiveArgument(Options options) throws IOException {
        if (options.positional().size() != 1) {
            throw new IllegalArgumentException("Give exactly one archive file");
        }
        Path archive = Path.of(options.positional().getFirst());
        if (!Files.isRegularFile(archive)) {
            throw new IOException("No archive at " + archive);
        }
        return archive;
    }

    static String formatSpeed(double speed) {
        return (speed == Math.rint(speed) ? Long.toString((long) speed) : Double.toString(speed)) + "x";
    }

    private static String url(DashboardServer dashboard) {
        InetSocketAddress address = dashboard.address();
        return "http://" + address.getHostString() + ":" + address.getPort() + "/";
    }

    private static void open(Options options, String url) {
        if (!options.flag("open")) {
            return;
        }
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (IOException | UnsupportedOperationException e) {
            System.out.println("Could not open a browser; visit " + url);
        }
    }

    private static void closeAll(AutoCloseable... resources) {
        for (AutoCloseable resource : resources) {
            try {
                resource.close();
            } catch (Exception e) {
                // Shutting down anyway.
            }
        }
    }

    private static String usage() {
        String scenarios = Arrays.stream(Scenario.values())
                .map(s -> String.format(Locale.ROOT, "  %-16s %s", s.id(), s.description()))
                .collect(Collectors.joining("\n"));
        return USAGE.formatted(scenarios + "\n");
    }
}
