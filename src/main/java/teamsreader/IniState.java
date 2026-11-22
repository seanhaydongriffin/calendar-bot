package teamsreader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal INI reader/writer compatible with the AutoIt IniRead/IniWrite format
 * used for teams_state.ini:
 * <pre>
 *   [email]
 *   uidvalidity=12345
 *   lastuid=6789
 *   [lastseen]
 *   19:...@thread.skype=2026-11-15T...
 * </pre>
 * Unknown sections/keys are preserved across a save().
 */
public final class IniState {
    private final Path file;
    private final Map<String, Map<String, String>> data = new LinkedHashMap<>();

    public IniState(Path file) {
        this.file = file;
        load();
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            String cur = null;
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) continue;
                if (line.startsWith("[") && line.endsWith("]")) {
                    cur = line.substring(1, line.length() - 1);
                    data.computeIfAbsent(cur, k -> new LinkedHashMap<>());
                } else if (cur != null) {
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        data.get(cur).put(line.substring(0, eq).trim(),
                                          line.substring(eq + 1).trim());
                    }
                }
            }
        } catch (IOException e) {
            Log.info("Could not read state file " + file + ": " + e.getMessage());
        }
    }

    public String read(String section, String key, String def) {
        Map<String, String> s = data.get(section);
        if (s == null) return def;
        return s.getOrDefault(key, def);
    }

    public int readInt(String section, String key, int def) {
        try { return Integer.parseInt(read(section, key, Integer.toString(def))); }
        catch (NumberFormatException e) { return def; }
    }

    public long readLong(String section, String key, long def) {
        try { return Long.parseLong(read(section, key, Long.toString(def))); }
        catch (NumberFormatException e) { return def; }
    }

    /** Set a value and persist immediately (IniWrite semantics). */
    public void write(String section, String key, String value) {
        data.computeIfAbsent(section, k -> new LinkedHashMap<>()).put(key, value);
        save();
    }

    private void save() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Map<String, String>> sec : data.entrySet()) {
            sb.append('[').append(sec.getKey()).append(']').append('\n');
            for (Map.Entry<String, String> kv : sec.getValue().entrySet()) {
                sb.append(kv.getKey()).append('=').append(kv.getValue()).append('\n');
            }
        }
        try {
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.info("Could not write state file " + file + ": " + e.getMessage());
        }
    }
}
