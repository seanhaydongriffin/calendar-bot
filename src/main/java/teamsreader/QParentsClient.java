package teamsreader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * QParents (QLD school portal) consent-request source.
 *
 * <p>The hard part - logging in past reCAPTCHA + MFA - is handled by the Python
 * helper {@code qp_login.py} (it drives the Pi's WebKit2 engine to mint the
 * captcha token, then completes the login and writes the session cookie to
 * {@code qparents_session.dat}). This class runs that helper to refresh the
 * session, then pulls ACTIONED consent requests over plain HTTPS and turns each
 * UPCOMING school event into a calendar {@link Event}. No Gemini - QParents data
 * is definitive. Dedupe by the consent's stable {@code reference}, so re-runs
 * upsert the same calendar entry.
 */
public final class QParentsClient {
    private static final String BASE = "https://qparents.qld.edu.au";

    private final Config cfg;
    private final GeminiClient gemini;   // used only to resolve free-text times
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    public String summary = "";

    public QParentsClient(Config cfg, GeminiClient gemini) { this.cfg = cfg; this.gemini = gemini; }

    /** Refresh the session, then collect upcoming events from actioned consents. */
    public List<Event> collect() {
        List<Event> out = new ArrayList<>();

        if (!Files.exists(cfg.home.resolve("qp_login.py"))
                || !Files.exists(cfg.home.resolve("qparents_cred.dat"))) {
            summary = "QParents: not configured (qp_login.py / qparents_cred.dat missing) - skipped.";
            Log.info(summary);
            return out;
        }

        if (!refreshSession()) {
            summary = "QParents: skipped (login helper failed or interactive MFA re-login needed).";
            Log.info(summary);
            return out;
        }
        String cookie = Util.readFile(cfg.qparentsSession).strip();
        if (cookie.isEmpty()) {
            summary = "QParents: skipped (empty session cookie).";
            Log.info(summary);
            return out;
        }

        try {
            List<String[]> students = students(get("/api/user?IncludeFutureStudents=true", cookie));
            if (students.isEmpty()) { summary = "QParents: no students found."; Log.info(summary); return out; }

            StringBuilder q = new StringBuilder("/api/consent/summary");
            for (int i = 0; i < students.size(); i++) {
                q.append(i == 0 ? "?" : "&").append("Students=")
                 .append(students.get(i)[0]).append('.').append(students.get(i)[1]);
            }
            JSONArray reqs = new JSONObject(get(q.toString(), cookie)).optJSONArray("consentRequests");
            if (reqs == null) reqs = new JSONArray();

            LocalDate today = LocalDate.now(cfg.zone);
            int scanned = 0, built = 0;
            for (int i = 0; i < reqs.length(); i++) {
                JSONObject r = reqs.getJSONObject(i);
                // ACTIONED only (a response was recorded); pending ones have null responseDate.
                if (r.isNull("responseDate") || r.optString("responseDate", "").isEmpty()) continue;
                // Pre-filter clearly-stale consents by dueDate so we don't fetch years of history.
                LocalDate due = parseDate(r.optString("dueDate", ""));
                if (due != null && due.isBefore(today.minusDays(60))) continue;

                String eqId = r.optString("eqId", "");
                String sch  = r.optString("schoolCode", "");
                String id   = r.optString("id", "");
                if (eqId.isEmpty() || sch.isEmpty() || id.isEmpty()) continue;
                scanned++;

                Event e = toEvent(get("/api/consent/" + eqId + "/" + id + "/" + sch, cookie), today);
                if (e != null) {
                    out.add(e);
                    built++;
                    Log.info("  QParents event: " + e.title + "  |  "
                            + (e.allDay ? e.start + " (all day)" : e.start)
                            + (e.location.isEmpty() ? "" : "  @ " + e.location));
                }
            }
            summary = "QParents: " + built + " upcoming event(s) from " + scanned + " actioned consent(s).";
            Log.info(summary);
        } catch (Exception ex) {
            summary = "QParents: error - " + ex.getMessage();
            Log.info(summary);
        }
        return out;
    }

    /** Run qp_login.py (non-interactive) to refresh the session cookie file. */
    private boolean refreshSession() {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "xvfb-run", "-a", "python3",
                    cfg.home.resolve("qp_login.py").toString(), "--non-interactive");
            pb.directory(cfg.home.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) Log.info("  [qp_login] " + line);
            }
            if (!p.waitFor(6, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                Log.info("  qp_login.py timed out.");
                return false;
            }
            return p.exitValue() == 0 && Files.exists(cfg.qparentsSession);
        } catch (Exception e) {
            Log.info("  qp_login.py failed to run: " + e.getMessage());
            return false;
        }
    }

    private String get(String path, String cookie) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + path))
                .timeout(Duration.ofSeconds(25))
                .header("accept", "application/json")
                .header("Cookie", cookie)
                .header("Client-Platform", "web")
                .header("Client-App-Version", "Web")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200)
            throw new RuntimeException("GET " + path + " -> HTTP " + resp.statusCode());
        return resp.body();
    }

    /** [eqId, schoolCode] per student from /api/user. */
    private static List<String[]> students(String userJson) {
        List<String[]> out = new ArrayList<>();
        try {
            JSONArray studs = new JSONObject(userJson).optJSONArray("students");
            if (studs != null) for (int i = 0; i < studs.length(); i++) {
                JSONObject s = studs.getJSONObject(i);
                String eqId = s.optString("oneSchoolId", "");
                String sch = "";
                JSONArray enr = s.optJSONArray("enrolments");
                if (enr != null && enr.length() > 0) sch = enr.getJSONObject(0).optString("schoolCode", "");
                if (!eqId.isEmpty() && !sch.isEmpty()) out.add(new String[]{eqId, sch});
            }
        } catch (Exception ignore) {}
        return out;
    }

    /** Build an Event from a consent detail, or null if not upcoming / unparseable. */
    private Event toEvent(String detJson, LocalDate today) {
        try {
            JSONObject root = new JSONObject(detJson);
            String title = root.optString("title", "").trim();
            String reference = root.optString("reference", "").trim();

            JSONObject data = findObjWithKey(root, "activityDate");
            if (data == null) return null;
            String activityDate = data.optString("activityDate", "").trim();   // YYYY-MM-DD
            LocalDate date = parseDate(activityDate);
            if (date == null || date.isBefore(today)) return null;             // upcoming (today+) only

            String location = data.optString("activityLocations", "").trim();
            String times    = data.optString("partDayStartEnd", "").trim();    // "5:15pm - 11:15pm" or ""
            if (title.isEmpty()) title = data.optString("title", "School event").trim();

            // Times, in order of reliability:
            //  1. partDayStartEnd  ("5:15pm - 11:15pm")   - in-school events
            //  2. departureTime / returnTime (8:00am / 4:00pm) - excursions
            //  3. free text in the consent -> ask Gemini (e.g. swimming carnivals)
            String[] se = parseTimes(times);
            if (se == null) {
                LocalTime dep = parseTime(data.optString("departureTime", ""));
                LocalTime ret = parseTime(data.optString("returnTime", ""));
                if (dep != null && ret != null) se = new String[]{ dep.format(OUT), ret.format(OUT) };
            }
            if (se == null && TIME_RX.matcher(detJson).find()) {
                se = geminiTimes(title, activityDate, data);
            }
            boolean allDay = (se == null);
            String start = allDay ? activityDate : activityDate + "T" + se[0];
            String end   = allDay ? "" : activityDate + "T" + se[1];

            JSONObject o = new JSONObject();
            o.put("title", title);
            o.put("start", start);
            o.put("end", end);
            o.put("all_day", allDay);
            o.put("location", location);
            o.put("source", "qparents");
            o.put("evidence", "");
            o.put("dedupe_key", "qparents:" + (reference.isEmpty() ? title + ":" + activityDate : reference));
            Event e = new Event(o);

            StringBuilder d = new StringBuilder("From QParents consent");

            // Activities - what the event actually is (array of strings).
            JSONArray acts = data.optJSONArray("activities");
            if (acts != null) {
                StringBuilder al = new StringBuilder();
                for (int i = 0; i < acts.length(); i++) {
                    String a = acts.optString(i, "").trim();
                    if (!a.isEmpty()) al.append("\n• ").append(a);
                }
                if (al.length() > 0) d.append("\n\nActivities:").append(al);
            }

            // "What to bring" + any early-arrival / pickup notes (the essential field).
            String addl = data.optString("additionalRequirements", "").trim();
            if (!addl.isEmpty()) d.append("\n\nWhat to bring / requirements:\n").append(addl);

            String cost = (data.has("cost") && !data.isNull("cost")) ? String.valueOf(data.get("cost")).trim() : "";
            if (!cost.isEmpty() && !cost.equals("0") && !cost.equals("0.0")) d.append("\n\nCost: $").append(cost);

            String dep = line("Depart", data.optString("departureLocation", ""), data.optString("departureTime", ""));
            String ret = line("Return", data.optString("returnLocation", ""), data.optString("returnTime", ""));
            if (!dep.isEmpty() || !ret.isEmpty()) {
                d.append('\n');
                if (!dep.isEmpty()) d.append('\n').append(dep);
                if (!ret.isEmpty()) d.append('\n').append(ret);
            }

            String cname  = data.optString("contactName", root.optString("contactName", "")).trim();
            String ctitle = data.optString("contactTitle", "").trim();
            if (!cname.isEmpty()) {
                d.append("\n\nContact: ").append(cname);
                if (!ctitle.isEmpty()) d.append(" (").append(ctitle).append(")");
            }
            e.description = d.toString();
            return e;
        } catch (Exception ex) {
            Log.info("  consent parse error: " + ex.getMessage());
            return null;
        }
    }

    private static String line(String label, String loc, String t) {
        loc = loc == null ? "" : loc.trim();
        t = t == null ? "" : t.trim();
        if (loc.isEmpty()) return "";
        return label + ": " + loc + (t.isEmpty() ? "" : " at " + t);
    }

    /** Recursively find the first JSONObject that contains {@code key}. */
    private static JSONObject findObjWithKey(Object node, String key) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            if (o.has(key)) return o;
            for (String k : o.keySet()) {
                JSONObject f = findObjWithKey(o.get(k), key);
                if (f != null) return f;
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                JSONObject f = findObjWithKey(a.get(i), key);
                if (f != null) return f;
            }
        }
        return null;
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isEmpty() || s.length() < 10) return null;
        try { return LocalDate.parse(s.substring(0, 10)); } catch (Exception e) { return null; }
    }

    // ---- time parsing: "5:15pm - 11:15pm" -> {"17:15:00","23:15:00"} ----------
    private static final DateTimeFormatter TF_HM = new DateTimeFormatterBuilder()
            .parseCaseInsensitive().appendPattern("h:mma").toFormatter(Locale.ENGLISH);
    private static final DateTimeFormatter TF_H = new DateTimeFormatterBuilder()
            .parseCaseInsensitive().appendPattern("ha").toFormatter(Locale.ENGLISH);
    private static final DateTimeFormatter OUT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static String[] parseTimes(String s) {
        if (s == null || s.isEmpty()) return null;
        String[] p = s.split("[-–]");                 // hyphen or en-dash
        if (p.length != 2) return null;
        LocalTime a = parseTime(p[0]), b = parseTime(p[1]);
        if (a == null || b == null) return null;
        return new String[]{ a.format(OUT), b.format(OUT) };
    }

    private static LocalTime parseTime(String t) {
        if (t == null) return null;
        t = t.toLowerCase().replace(" ", "").replace(".", "").trim();
        if (t.isEmpty()) return null;
        for (DateTimeFormatter f : new DateTimeFormatter[]{ TF_HM, TF_H }) {
            try { return LocalTime.parse(t, f); } catch (Exception ignore) {}
        }
        return null;
    }

    // ---- Gemini fallback for free-text times (e.g. swimming carnivals) -------
    private static final Pattern TIME_RX =
            Pattern.compile("\\d{1,2}:\\d{2}\\s*[ap]m", Pattern.CASE_INSENSITIVE);
    private static final Set<String> SKIP_KEYS = Set.of(
            "parentName", "studentName", "emergencyContactName", "emergencyContactPhoneNumber",
            "emergencyContactEmailAddress", "parentPhoneNumber", "parentEmailAddress",
            "contactPhone", "contactEmail", "contactTitle");
    private static final DateTimeFormatter HM = new DateTimeFormatterBuilder()
            .parseCaseInsensitive().appendPattern("H:mm").toFormatter(Locale.ENGLISH);

    /** No structured time field, but the consent text mentions times: ask Gemini to
     *  resolve the event's start/end on activityDate. {HH:mm:ss, HH:mm:ss} or null. */
    private String[] geminiTimes(String title, String activityDate, JSONObject data) {
        try {
            StringBuilder ctx = new StringBuilder();
            for (String k : data.keySet()) {
                if (SKIP_KEYS.contains(k)) continue;
                Object v = data.get(k);
                if (v instanceof String) {
                    String s = ((String) v).trim();
                    if (!s.isEmpty() && s.length() <= 2000) ctx.append(k).append(": ").append(s).append('\n');
                }
            }
            String prompt =
                "A school event titled \"" + title + "\" takes place on " + activityDate
              + " (timezone Australia/Brisbane). From the consent details below, determine the event's"
              + " START and END clock time on that day.\n"
              + "Return ONLY JSON {\"start\":\"HH:mm\",\"end\":\"HH:mm\"} in 24-hour time, or"
              + " {\"start\":null,\"end\":null} if no clear time is stated.\n"
              + "Use the time students ARRIVE or the activity BEGINS as start, and the time it"
              + " CONCLUDES as end; ignore a later 'collect/pick up by' buffer time as the end if a"
              + " clear conclusion time is given.\n\nDETAILS:\n" + ctx;
            JSONArray parts = new JSONArray();
            parts.put(GeminiClient.textPart(prompt));
            String resp = gemini.generate(parts);
            LocalTime a = parseHHmm(Util.jsonStr(resp, "start"));
            LocalTime b = parseHHmm(Util.jsonStr(resp, "end"));
            if (a != null && b != null) {
                Log.info("    QParents: Gemini resolved times for '" + title + "' -> "
                        + a.format(OUT) + " to " + b.format(OUT));
                return new String[]{ a.format(OUT), b.format(OUT) };
            }
        } catch (Exception ex) {
            Log.info("    QParents: Gemini time extraction failed - " + ex.getMessage());
        }
        return null;
    }

    private static LocalTime parseHHmm(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty() || s.equalsIgnoreCase("null")) return null;
        try { return LocalTime.parse(s, HM); } catch (Exception e) { return null; }
    }
}
