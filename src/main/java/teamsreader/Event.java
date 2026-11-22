package teamsreader;

import org.json.JSONObject;

/** One calendar event as returned by Gemini (see the prompt schema). */
public final class Event {
    public final String title;
    public final String start;      // local ISO8601 or "" / "null"
    public final String end;        // local ISO8601 or "" / "null"
    public final boolean allDay;
    public final String location;
    public final String confidence; // raw numeric text, "" if absent
    public final String source;     // "text" | "image" | "both"
    public final String evidence;
    public final String dedupeKey;
    public final String sourceRef;  // Gemini's [ref N] of the deciding message ("" / "null" if none)
    public String sourceUrl = "";   // resolved Teams deep link (set by Main), "" if not from a Teams message

    public Event(JSONObject o) {
        this.title      = str(o, "title");
        this.start      = str(o, "start");
        this.end        = str(o, "end");
        this.allDay     = o.optBoolean("all_day", false);
        this.location   = str(o, "location");
        this.source     = str(o, "source");
        this.evidence   = str(o, "evidence");
        this.dedupeKey  = str(o, "dedupe_key");
        this.sourceRef  = str(o, "source_ref");
        this.confidence = o.has("confidence") && !o.isNull("confidence")
                ? trimNum(o.get("confidence").toString()) : "";
    }

    private static String str(JSONObject o, String k) {
        return (o.has(k) && !o.isNull(k)) ? o.optString(k, "") : "";
    }
    private static String trimNum(String s) { return s == null ? "" : s.trim(); }

    public boolean hasStart() {
        return start != null && !start.isEmpty() && !"null".equals(start);
    }
    public boolean hasEnd() {
        return end != null && !end.isEmpty() && !"null".equals(end);
    }
}
