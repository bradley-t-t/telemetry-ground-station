package telemetry.app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Command-line options: {@code --name value}, repeatable where noted, plus bare arguments. */
final class Options {

    private final Map<String, List<String>> values = new HashMap<>();
    private final List<String> positional = new ArrayList<>();

    static Options parse(String[] args, Set<String> allowed, Set<String> flags) {
        Options options = new Options();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                options.positional.add(arg);
                continue;
            }
            String name = arg.substring(2);
            if (flags.contains(name)) {
                options.values.computeIfAbsent(name, k -> new ArrayList<>()).add("true");
                continue;
            }
            if (!allowed.contains(name)) {
                throw new IllegalArgumentException("Unknown option --" + name);
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("--" + name + " needs a value");
            }
            options.values.computeIfAbsent(name, k -> new ArrayList<>()).add(args[++i]);
        }
        return options;
    }

    String get(String name, String fallback) {
        List<String> list = values.get(name);
        return list == null ? fallback : list.getLast();
    }

    int getInt(String name, int fallback) {
        String value = get(name, null);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + name + " must be a whole number");
        }
    }

    double getDouble(String name, double fallback) {
        String value = get(name, null);
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + name + " must be a number");
        }
    }

    boolean flag(String name) {
        return values.containsKey(name);
    }

    List<String> all(String name) {
        return values.getOrDefault(name, List.of());
    }

    List<String> positional() {
        return positional;
    }
}
