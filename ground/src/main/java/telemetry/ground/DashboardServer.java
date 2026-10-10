package telemetry.ground;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import telemetry.protocol.Channel;
import telemetry.protocol.Dictionary;
import telemetry.protocol.Limits;
import telemetry.protocol.PacketDefinition;

/**
 * Serves the dashboard and its API from the JDK's built-in HTTP server, one virtual thread
 * per request. Live data goes out as Server-Sent Events: each connection is sent every new
 * snapshot, plus the rows of plot history it has not seen yet.
 *
 * <pre>
 *  GET  /                     the dashboard
 *  GET  /api/dictionary       packets and channels, with limits
 *  GET  /api/stream           Server-Sent Events, ten a second
 *  GET  /api/history?channel  one channel's whole history
 *  GET  /api/report           the post-flight report as text
 *  POST /api/alarms/ack?id    acknowledge one alarm; /api/alarms/ack-all for all of them
 *  POST /api/flights?scenario&amp;speed   start a simulated flight, when a simulator is attached
 * </pre>
 */
public final class DashboardServer implements AutoCloseable {

    static final List<String> PLOT_CHANNELS = List.of(
            "S1_ALTITUDE", "S1_DOWNRANGE", "S1_VELOCITY", "S1_ACCEL", "S1_DYN_PRESSURE", "S1_THROTTLE",
            "S2_ALTITUDE", "S2_DOWNRANGE", "S2_VELOCITY", "S2_ACCEL", "S2_THROTTLE");

    private static final int BACKFILL_ROWS = 3_000;
    private static final long KEEP_ALIVE_MILLIS = 1_000;
    private static final Map<String, String[]> STATIC_FILES = Map.of(
            "/", new String[] {"index.html", "text/html; charset=utf-8"},
            "/index.html", new String[] {"index.html", "text/html; charset=utf-8"},
            "/app.js", new String[] {"app.js", "text/javascript; charset=utf-8"},
            "/style.css", new String[] {"style.css", "text/css; charset=utf-8"});

    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final GroundStation station;
    private final DemoControl demo;
    private final int[] plotIndexes;
    private final String dictionaryJson;
    private volatile boolean running = true;

    public DashboardServer(InetSocketAddress address, GroundStation station, DemoControl demo) throws IOException {
        this.station = station;
        this.demo = demo;
        Dictionary dictionary = station.dictionary();
        this.plotIndexes = PLOT_CHANNELS.stream().mapToInt(name -> dictionary.channel(name).index()).toArray();
        this.dictionaryJson = dictionaryJson(dictionary, demo);
        this.server = HttpServer.create(address, 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> handle(exchange, this::serveStatic));
        server.createContext("/api/dictionary", exchange -> handle(exchange, e -> send(e, 200, "application/json", dictionaryJson)));
        server.createContext("/api/stream", exchange -> handle(exchange, this::stream));
        server.createContext("/api/history", exchange -> handle(exchange, this::history));
        server.createContext("/api/report", exchange -> handle(exchange, e -> send(e, 200, "text/plain; charset=utf-8", station.report())));
        server.createContext("/api/alarms/ack", exchange -> handle(exchange, this::acknowledge));
        server.createContext("/api/alarms/ack-all", exchange -> handle(exchange, this::acknowledgeAll));
        server.createContext("/api/flights", exchange -> handle(exchange, this::launch));
    }

    public void start() {
        server.start();
    }

    public InetSocketAddress address() {
        return server.getAddress();
    }

    @Override
    public void close() {
        running = false;
        server.stop(0);
        executor.shutdownNow();
    }

    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private void handle(HttpExchange exchange, Handler handler) {
        try (exchange) {
            handler.handle(exchange);
        } catch (IOException e) {
            // The browser went away mid-response. Nothing to clean up.
        } catch (RuntimeException e) {
            try {
                send(exchange, 500, "text/plain; charset=utf-8", "Internal error: " + e.getMessage());
            } catch (IOException ignored) {
                // Already failing; the connection is gone.
            }
        }
    }

    private void serveStatic(HttpExchange exchange) throws IOException {
        String[] file = STATIC_FILES.get(exchange.getRequestURI().getPath());
        if (file == null) {
            send(exchange, 404, "text/plain; charset=utf-8", "Not found");
            return;
        }
        if (!requireMethod(exchange, "GET")) {
            return;
        }
        try (InputStream in = DashboardServer.class.getResourceAsStream("/web/" + file[0])) {
            if (in == null) {
                send(exchange, 404, "text/plain; charset=utf-8", "Not found");
                return;
            }
            byte[] body = in.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", file[1]);
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
    }

    private void stream(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) {
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        OutputStream out = exchange.getResponseBody();
        long version = -1;
        int flight = -1;
        long cursor = 0;
        try {
            while (running) {
                Snapshot snapshot = station.feed().awaitNewer(version, KEEP_ALIVE_MILLIS);
                if (snapshot == null) {
                    out.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    continue;
                }
                version = snapshot.version();
                if (snapshot.flight() != flight) {
                    flight = snapshot.flight();
                    cursor = 0;
                }
                History.Slice slice = snapshot.history().since(cursor, plotIndexes, BACKFILL_ROWS);
                cursor = slice.next();
                String json = snapshot.json();
                StringBuilder message = new StringBuilder(json.length() + 64 + slice.rows().length * 120);
                message.append("data: ").append(json, 0, json.length() - 1).append(",\"history\":[");
                double[][] rows = slice.rows();
                for (int r = 0; r < rows.length; r++) {
                    if (r > 0) {
                        message.append(',');
                    }
                    message.append('[');
                    for (int c = 0; c < rows[r].length; c++) {
                        if (c > 0) {
                            message.append(',');
                        }
                        Json.number(message, rows[r][c]);
                    }
                    message.append(']');
                }
                message.append("]}\n\n");
                out.write(message.toString().getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void history(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) {
            return;
        }
        Channel channel = station.dictionary().findChannel(query(exchange).getOrDefault("channel", ""));
        if (channel == null) {
            send(exchange, 404, "text/plain; charset=utf-8", "Unknown channel");
            return;
        }
        Snapshot snapshot = station.feed().latest();
        double[][] series = snapshot.history().series(channel.index());
        Json json = new Json(series[0].length * 24 + 64);
        json.beginObject().field("channel", channel.name()).field("flight", snapshot.flight());
        json.name("met").beginArray();
        for (double met : series[0]) {
            json.value(met);
        }
        json.endArray().name("values").beginArray();
        for (double value : series[1]) {
            json.value(value);
        }
        json.endArray().endObject();
        send(exchange, 200, "application/json", json.toString());
    }

    private void acknowledge(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        try {
            station.acknowledge(Integer.parseInt(query(exchange).getOrDefault("id", "")));
            send(exchange, 204, null, null);
        } catch (NumberFormatException e) {
            send(exchange, 400, "text/plain; charset=utf-8", "id must be a number");
        }
    }

    private void acknowledgeAll(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        station.acknowledgeAll();
        send(exchange, 204, null, null);
    }

    private void launch(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        if (demo == null) {
            send(exchange, 404, "text/plain; charset=utf-8", "This ground station has no simulator attached");
            return;
        }
        Map<String, String> query = query(exchange);
        double speed;
        try {
            speed = Double.parseDouble(query.getOrDefault("speed", "1"));
        } catch (NumberFormatException e) {
            send(exchange, 400, "text/plain; charset=utf-8", "speed must be a number");
            return;
        }
        if (!(speed >= 0.25 && speed <= 20)) {
            send(exchange, 400, "text/plain; charset=utf-8", "speed must be between 0.25 and 20");
            return;
        }
        try {
            demo.launch(query.getOrDefault("scenario", "nominal"), speed);
            send(exchange, 202, null, null);
        } catch (IllegalArgumentException e) {
            send(exchange, 400, "text/plain; charset=utf-8", e.getMessage());
        }
    }

    private static boolean requireMethod(HttpExchange exchange, String method) throws IOException {
        if (exchange.getRequestMethod().equals(method)) {
            return true;
        }
        exchange.getResponseHeaders().set("Allow", method);
        send(exchange, 405, "text/plain; charset=utf-8", "Use " + method);
        return false;
    }

    private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        if (body == null) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> params = new HashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null) {
            return params;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            params.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return params;
    }

    private static String dictionaryJson(Dictionary dictionary, DemoControl demo) {
        Json json = new Json(32 * 1024);
        json.beginObject();
        json.name("packets").beginArray();
        for (PacketDefinition packet : dictionary.packets()) {
            json.beginObject()
                    .field("apid", packet.apid())
                    .field("name", packet.name())
                    .field("rate", packet.rateHz())
                    .field("description", packet.description())
                    .endObject();
        }
        json.endArray();
        json.name("channels").beginArray();
        for (Channel channel : dictionary.channels()) {
            Limits limits = channel.limits();
            json.beginObject()
                    .field("name", channel.name())
                    .field("packet", dictionary.packet(channel.apid()).name())
                    .field("type", channel.type().name().toLowerCase(Locale.ROOT))
                    .field("kind", channel.kind().name().toLowerCase(Locale.ROOT))
                    .field("units", channel.units())
                    .field("description", channel.description());
            json.name("limits").beginArray()
                    .value(limits.redLow()).value(limits.yellowLow()).value(limits.yellowHigh()).value(limits.redHigh())
                    .endArray();
            json.field("maxRate", channel.maxRate());
            if (channel.hasCondition()) {
                String gate = dictionary.channels().get(channel.whenChannel()).name();
                double value = channel.whenValue();
                String condition = value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
                json.field("when", Double.isNaN(value) ? gate : gate + "=" + condition);
            } else {
                json.name("when").nullValue();
            }
            json.endObject();
        }
        json.endArray();
        json.name("plot").beginArray();
        for (String name : PLOT_CHANNELS) {
            json.value(name);
        }
        json.endArray();
        json.name("scenarios");
        if (demo == null) {
            json.nullValue();
        } else {
            json.beginArray();
            for (DemoControl.Option option : demo.scenarios()) {
                json.beginObject()
                        .field("id", option.id())
                        .field("label", option.label())
                        .field("description", option.description())
                        .endObject();
            }
            json.endArray();
        }
        json.endObject();
        return json.toString();
    }
}
