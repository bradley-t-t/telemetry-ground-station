package telemetry.ground;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import telemetry.protocol.Dictionary;
import telemetry.protocol.FlightEvent;
import telemetry.protocol.PacketEncoder;
import telemetry.vehicle.UdpSink;

/** The live path: real UDP in, the processor thread, and the HTTP API out. */
class DashboardServerTest {

    private final Dictionary dictionary = Dictionary.standard();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private GroundStation station;
    private DashboardServer dashboard;
    private String base;

    @BeforeEach
    void start() throws Exception {
        station = new GroundStation(dictionary, new InetSocketAddress("127.0.0.1", 0), null);
        station.start();
        dashboard = new DashboardServer(new InetSocketAddress("127.0.0.1", 0), station, null);
        dashboard.start();
        base = "http://127.0.0.1:" + dashboard.address().getPort();
    }

    @AfterEach
    void stop() throws Exception {
        dashboard.close();
        station.close();
    }

    @Test
    void servesThePageAndTheDictionary() throws Exception {
        HttpResponse<String> page = get("/");
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("<title>Ground Station</title>"));

        HttpResponse<String> dictionaryJson = get("/api/dictionary");
        assertEquals(200, dictionaryJson.statusCode());
        assertTrue(dictionaryJson.body().contains("\"name\":\"S1_ENG5_PC\""));
        assertTrue(dictionaryJson.body().contains("\"scenarios\":null"));

        assertEquals(404, get("/../build.gradle.kts").statusCode());
        assertEquals(404, http.send(post("/api/flights?scenario=nominal"), HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void packetsSentOverUdpReachTheEventStream() throws Exception {
        PacketEncoder events = new PacketEncoder(dictionary.packet("EVENTS"));
        events.set(dictionary.channel("EVENT_CODE"), FlightEvent.LIFTOFF.code())
                .set(dictionary.channel("EVENT_TIME"), 1015.0);
        PacketEncoder nav = new PacketEncoder(dictionary.packet("S1_NAV"));
        nav.set(dictionary.channel("S1_ALTITUDE"), 12.5);
        try (UdpSink sink = new UdpSink(station.udpAddress())) {
            sink.send(events.encode(1015.0));
            sink.send(nav.encode(1020.0));
        }

        String message = firstMessageContaining("\"Liftoff\"");
        assertTrue(message.contains("\"state\":\"LIVE\""), message);
        assertTrue(message.contains("\"met\":5"), message);
        assertTrue(message.contains("12.5"), message);
    }

    private String firstMessageContaining(String text) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/stream")).build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals("text/event-stream; charset=utf-8", response.headers().firstValue("Content-Type").orElse(""));
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while (System.nanoTime() < deadline && (line = reader.readLine()) != null) {
                if (line.startsWith("data: ") && line.contains(text)) {
                    return line;
                }
            }
        }
        throw new AssertionError("No stream message contained " + text);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest post(String path) {
        return HttpRequest.newBuilder(URI.create(base + path)).POST(HttpRequest.BodyPublishers.noBody()).build();
    }
}
