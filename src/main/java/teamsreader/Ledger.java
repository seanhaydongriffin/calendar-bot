package teamsreader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;

/**
 * The dedupe ledger (teams_events_ledger.txt). Each line is
 * {@code dedupe_key|start|title}. It is fed back into the Gemini prompt so the
 * model reuses the EXACT dedupe_key for an event already on the calendar, which
 * is what keeps re-runs from creating duplicates. Format is byte-compatible with
 * the AutoIt version, so the existing file keeps working.
 */
public final class Ledger {
    private final Config cfg;

    public Ledger(Config cfg) { this.cfg = cfg; }

    private String readRaw() {
        try {
            if (!Files.exists(cfg.ledgerFile)) return "";
            return Files.readString(cfg.ledgerFile, StandardCharsets.UTF_8).trim();
        } catch (IOException e) { return ""; }
    }

    /** The "EVENTS ALREADY ON THE CALENDAR" block injected into the model prompt. */
    public String promptBlock() {
        String raw = readRaw();
        if (raw.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(
            "EVENTS ALREADY ON THE CALENDAR - if one of the events you extract is the\n" +
            "SAME real event as one of these, set its dedupe_key to that EXACT key (do not\n" +
            "invent a new one); only use a new key for a genuinely new event:\n");
        int n = 0;
        for (String line : raw.split("\n")) {
            String ln = line.trim();
            if (ln.isEmpty()) continue;
            String[] p = ln.split("\\|", -1);
            if (p.length < 3) continue;
            sb.append("- dedupe_key \"").append(p[0]).append("\": \"")
              .append(p[2]).append("\" at ").append(p[1]).append('\n');
            n++;
        }
        return n > 0 ? sb.toString() : "";
    }

    /** Merge this run's events (newest wins) with the surviving older entries,
     *  prune anything past the retention window, and rewrite the file. */
    public void record(List<Event> events) {
        Instant cut = Instant.now().minusSeconds((long) cfg.ledgerDays * 86400);
        StringBuilder out = new StringBuilder();
        StringBuilder seen = new StringBuilder("|");

        // current run's events first - they are the freshest truth
        for (Event e : events) {
            String k = e.dedupeKey, st = e.start, ti = e.title;
            if (k.isEmpty() || ti.isEmpty()) continue;
            if (seen.indexOf("|" + k + "|") >= 0) continue;
            seen.append(k).append('|');
            ti = ti.replace("|", "/");   // protect the delimiter
            out.append(k).append('|').append(st).append('|').append(ti).append('\n');
        }

        // carry forward older ledger entries not superseded and not expired
        for (String line : readRaw().split("\n")) {
            String ln = line.trim();
            if (ln.isEmpty()) continue;
            String[] p = ln.split("\\|", -1);
            if (p.length < 3) continue;
            if (seen.indexOf("|" + p[0] + "|") >= 0) continue;
            if (!p[1].isEmpty() && !"null".equals(p[1])) {
                Instant when = TimeUtil.parseIso(p[1]);
                if (when != null && when.isBefore(cut)) continue;   // expired
            }
            seen.append(p[0]).append('|');
            out.append(p[0]).append('|').append(p[1]).append('|').append(p[2]).append('\n');
        }

        try {
            Files.writeString(cfg.ledgerFile, out.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.info("Could not write ledger: " + e.getMessage());
        }
    }
}
