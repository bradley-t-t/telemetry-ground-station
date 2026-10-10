package telemetry.protocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The telemetry dictionary: every packet the vehicle sends and every channel inside it.
 * The vehicle encodes from it and the ground station decodes and checks limits from it,
 * so the two sides cannot drift apart. The file format is described at the top of
 * {@code dictionary.txt}.
 */
public final class Dictionary {

    private static final int CHANNEL_FIELDS = 11;

    private final List<PacketDefinition> packets;
    private final List<Channel> channels;
    private final Map<String, Channel> channelsByName;
    private final PacketDefinition[] packetsByApid = new PacketDefinition[SpacePacket.MAX_APID + 1];

    private Dictionary(List<PacketDefinition> packets) {
        this.packets = List.copyOf(packets);
        List<Channel> all = new ArrayList<>();
        Map<String, Channel> byName = new LinkedHashMap<>();
        for (PacketDefinition packet : packets) {
            packetsByApid[packet.apid()] = packet;
            for (Channel channel : packet.channels()) {
                all.add(channel);
                byName.put(channel.name(), channel);
            }
        }
        this.channels = List.copyOf(all);
        this.channelsByName = Collections.unmodifiableMap(byName);
    }

    /** The dictionary bundled with this build. */
    public static Dictionary standard() {
        return Standard.INSTANCE;
    }

    public static Dictionary load(String resource) {
        try (InputStream in = Dictionary.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("No dictionary resource at " + resource);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return parse(resource, reader.lines().toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Dictionary parse(String source, List<String> lines) {
        List<PendingPacket> pending = new ArrayList<>();
        PendingPacket current = null;
        for (int i = 0; i < lines.size(); i++) {
            int lineNumber = i + 1;
            String line = lines.get(i).strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("packet ")) {
                String[] fields = line.split("\\s+", 5);
                if (fields.length < 5) {
                    throw error(source, lineNumber, "expected: packet <apid> <name> <rate_hz> <description>");
                }
                int apid = parseApid(source, lineNumber, fields[1]);
                double rate = parseNumber(source, lineNumber, fields[3], "rate");
                current = new PendingPacket(lineNumber, apid, fields[2], rate, fields[4]);
                pending.add(current);
            } else {
                if (current == null) {
                    throw error(source, lineNumber, "channel row appears before any packet line");
                }
                String[] fields = line.split("\\s+", CHANNEL_FIELDS);
                if (fields.length < CHANNEL_FIELDS) {
                    throw error(source, lineNumber, "expected " + CHANNEL_FIELDS + " fields, found " + fields.length);
                }
                current.rows.add(new Row(lineNumber, fields));
            }
        }
        return build(source, pending);
    }

    private static Dictionary build(String source, List<PendingPacket> pending) {
        Map<String, Integer> indexByName = new HashMap<>();
        Map<Integer, String> apids = new HashMap<>();
        int index = 0;
        for (PendingPacket packet : pending) {
            String previous = apids.putIfAbsent(packet.apid, packet.name);
            if (previous != null) {
                throw error(source, packet.line, "APID " + hex(packet.apid) + " is already used by " + previous);
            }
            if (packet.rows.isEmpty()) {
                throw error(source, packet.line, "packet " + packet.name + " has no channels");
            }
            for (Row row : packet.rows) {
                String name = row.fields[0];
                if (indexByName.putIfAbsent(name, index++) != null) {
                    throw error(source, row.line, "channel " + name + " is defined twice");
                }
            }
        }

        List<PacketDefinition> packets = new ArrayList<>();
        index = 0;
        for (PendingPacket packet : pending) {
            List<Channel> channels = new ArrayList<>();
            int offset = SpacePacket.HEADER_LENGTH;
            for (Row row : packet.rows) {
                String[] f = row.fields;
                ChannelType type = parseEnum(source, row.line, () -> ChannelType.parse(f[1]), "type", f[1]);
                ChannelClass kind = parseEnum(source, row.line, () -> ChannelClass.parse(f[2]), "class", f[2]);
                Limits limits = new Limits(
                        optional(source, row.line, f[4], "red_lo"),
                        optional(source, row.line, f[5], "yel_lo"),
                        optional(source, row.line, f[6], "yel_hi"),
                        optional(source, row.line, f[7], "red_hi"));
                checkOrdering(source, row.line, limits);
                double maxRate = optional(source, row.line, f[8], "rate");
                int whenChannel = -1;
                double whenValue = Double.NaN;
                if (!f[9].equals("-")) {
                    String[] parts = f[9].split("=", 2);
                    Integer gate = indexByName.get(parts[0]);
                    if (gate == null) {
                        throw error(source, row.line, "when refers to unknown channel " + parts[0]);
                    }
                    whenChannel = gate;
                    if (parts.length == 2) {
                        whenValue = parseNumber(source, row.line, parts[1], "when value");
                    }
                }
                String units = f[3].equals("-") ? "" : f[3];
                channels.add(new Channel(index++, channels.size(), offset, packet.apid, f[0], type, kind,
                        units, limits, maxRate, whenChannel, whenValue, f[10]));
                offset += type.size();
            }
            packets.add(new PacketDefinition(packet.apid, packet.name, packet.rate, packet.description,
                    channels, offset - SpacePacket.HEADER_LENGTH));
        }
        return new Dictionary(packets);
    }

    public List<PacketDefinition> packets() {
        return packets;
    }

    public List<Channel> channels() {
        return channels;
    }

    /** The packet with this APID, or null if the dictionary does not define one. */
    public PacketDefinition packet(int apid) {
        return apid >= 0 && apid < packetsByApid.length ? packetsByApid[apid] : null;
    }

    public PacketDefinition packet(String name) {
        for (PacketDefinition packet : packets) {
            if (packet.name().equals(name)) {
                return packet;
            }
        }
        throw new IllegalArgumentException("No packet named " + name);
    }

    public Channel channel(String name) {
        Channel channel = channelsByName.get(name);
        if (channel == null) {
            throw new IllegalArgumentException("No channel named " + name);
        }
        return channel;
    }

    public Channel findChannel(String name) {
        return channelsByName.get(name);
    }

    private static void checkOrdering(String source, int line, Limits limits) {
        double[] ordered = {limits.redLow(), limits.yellowLow(), limits.yellowHigh(), limits.redHigh()};
        double previous = Double.NEGATIVE_INFINITY;
        for (double bound : ordered) {
            if (Double.isNaN(bound)) {
                continue;
            }
            if (bound < previous) {
                throw error(source, line, "limits must run red_lo <= yel_lo <= yel_hi <= red_hi");
            }
            previous = bound;
        }
    }

    private static int parseApid(String source, int line, String token) {
        try {
            int apid = token.startsWith("0x") ? Integer.parseInt(token.substring(2), 16) : Integer.parseInt(token);
            if (apid < 0 || apid > SpacePacket.MAX_APID) {
                throw error(source, line, "APID " + token + " is outside 0..0x7FF");
            }
            return apid;
        } catch (NumberFormatException e) {
            throw error(source, line, "APID " + token + " is not a number");
        }
    }

    private static double optional(String source, int line, String token, String field) {
        return token.equals("-") ? Double.NaN : parseNumber(source, line, token, field);
    }

    private static double parseNumber(String source, int line, String token, String field) {
        try {
            return Double.parseDouble(token);
        } catch (NumberFormatException e) {
            throw error(source, line, field + " '" + token + "' is not a number");
        }
    }

    private static <T> T parseEnum(String source, int line, Supplier<T> parser,
                                   String field, String token) {
        try {
            return parser.get();
        } catch (IllegalArgumentException e) {
            throw error(source, line, "unknown " + field + " '" + token + "'");
        }
    }

    private static IllegalArgumentException error(String source, int line, String message) {
        return new IllegalArgumentException(source + ":" + line + ": " + message);
    }

    private static String hex(int apid) {
        return String.format("0x%03X", apid);
    }

    private record Row(int line, String[] fields) {
    }

    private static final class PendingPacket {
        final int line;
        final int apid;
        final String name;
        final double rate;
        final String description;
        final List<Row> rows = new ArrayList<>();

        PendingPacket(int line, int apid, String name, double rate, String description) {
            this.line = line;
            this.apid = apid;
            this.name = name;
            this.rate = rate;
            this.description = description;
        }
    }

    private static final class Standard {
        static final Dictionary INSTANCE = load("/telemetry/dictionary.txt");
    }
}
