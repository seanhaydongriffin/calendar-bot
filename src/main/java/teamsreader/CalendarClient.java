package teamsreader;

import org.json.JSONObject;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

/**
 * Google Calendar upsert over curl (no impersonation needed). Each event id is
 * derived from the model's dedupe_key, so a re-run UPDATES an event (409 -> PUT)
 * rather than creating a duplicate. Auth: the long-lived refresh_token in
 * google_refresh_token.dat is traded for an access_token each run.
 */
public final class CalendarClient {
    private final Config cfg;
    private final Curl curl;
    private final Ledger ledger;

    private String access = "";
    private long accessExp = 0;
    public String summary = "";   // one-line result for the run summary

    public CalendarClient(Config cfg, Curl curl, Ledger ledger) {
        this.cfg = cfg; this.curl = curl; this.ledger = ledger;
    }

    private String[] client() {
        String s = Util.readFile(cfg.gClientFile).strip();
        if (s.isEmpty()) return new String[]{"", ""};
        String[] p = s.split("\\|", -1);
        return new String[]{ p[0].strip(), p.length >= 2 ? p[1].strip() : "" };
    }

    /** Return a valid Google access_token, refreshing as needed. "" on failure. */
    private String ensureAccess() {
        if (!access.isEmpty() && Instant.now().getEpochSecond() < accessExp - 120) return access;
        String rt = Util.readFile(cfg.gRefreshFile).strip();
        if (rt.isEmpty()) {
            Log.info("Google: no refresh token (" + cfg.gRefreshFile + "). Run the one-time consent on a "
                    + "desktop and copy that file to the Pi - see README.");
            return "";
        }
        String[] c = client();
        List<String> args = Arrays.asList(
                cfg.gTokenUrl, "-s", "-X", "POST",
                "--data-urlencode", "client_id=" + c[0],
                "--data-urlencode", "client_secret=" + c[1],
                "--data-urlencode", "refresh_token=" + rt,
                "--data-urlencode", "grant_type=refresh_token");
        String resp = curl.text(args);
        String acc = Util.jsonStr(resp, "access_token");
        if (acc.isEmpty()) {
            Util.writeFile(cfg.home.resolve("google_auth_error.json"), resp);
            Log.info("Google token refresh failed; saved google_auth_error.json.");
            return "";
        }
        String exp = Util.jsonNum(resp, "expires_in");
        accessExp = Instant.now().getEpochSecond() + (exp.isEmpty() ? 3600 : Long.parseLong(exp));
        access = acc;
        return acc;
    }

    public void push(List<Event> events) {
        String[] c = client();
        if (c[0].isEmpty()) {
            summary = "Calendar: push skipped (no google_client.dat).";
            Log.info(summary);
            return;
        }
        String acc = ensureAccess();
        if (acc.isEmpty()) {
            summary = "Calendar: push skipped (no Google access token).";
            Log.info(summary);
            return;
        }

        String cal = Util.urlEncodePath(cfg.gCalId);
        int ok = 0, upd = 0, err = 0, skip = 0;
        for (Event e : events) {
            if (e.title.isEmpty() || !e.hasStart()) { skip++; continue; }

            JSONObject body = new JSONObject();
            String id = Util.eventId(e.dedupeKey, e.title + "|" + e.start);
            body.put("id", id);
            body.put("summary", e.title);
            body.put("location", e.location);

            // Description: the readable text/summary from Teams (as before). The deep
            // link now lives in the dedicated URL field (below), not in here.
            String desc;
            if (!e.description.isEmpty()) {
                desc = e.description;                       // explicit (e.g. QParents consent)
            } else {
                desc = "From Teams COMMUNICATION!!!";
                if (!e.confidence.isEmpty()) desc += " (confidence " + e.confidence + ")";
                if (!e.evidence.isEmpty())   desc += " - " + e.evidence;
            }
            body.put("description", desc);

            // Deep link in Google's dedicated URL field ("source") - shows as a clickable
            // "Open Teams message" link in the Calendar event popup.
            if (!e.sourceUrl.isEmpty()) {
                body.put("source", new JSONObject()
                        .put("url", e.sourceUrl)
                        .put("title", "Open Teams message"));
            }

            if (e.allDay) {
                String d1 = e.start.substring(0, 10);
                String d2 = e.hasEnd() ? e.end.substring(0, 10) : datePlusDays(d1, 1);
                body.put("start", new JSONObject().put("date", d1));
                body.put("end",   new JSONObject().put("date", d2));
            } else {
                String end = e.hasEnd() ? e.end : isoShiftHours(e.start, 1);
                body.put("start", new JSONObject().put("dateTime", e.start).put("timeZone", cfg.zone.getId()));
                body.put("end",   new JSONObject().put("dateTime", end).put("timeZone", cfg.zone.getId()));
            }

            Path reqFile = cfg.home.resolve("_gcal_req.json");
            Util.writeFile(reqFile, body.toString());

            Curl.Result ins = curl.run(Arrays.asList(
                    cfg.gCalBase + "/calendars/" + cal + "/events", "-s", "-X", "POST",
                    "-H", "Authorization: Bearer " + acc,
                    "-H", "Content-Type: application/json",
                    "--data-binary", "@" + reqFile));

            if (ins.httpCode == 200 || ins.httpCode == 201) {
                Log.info("  calendar + created: " + e.title);
                ok++;
            } else if (ins.httpCode == 409) {
                Curl.Result up = curl.run(Arrays.asList(
                        cfg.gCalBase + "/calendars/" + cal + "/events/" + id, "-s", "-X", "PUT",
                        "-H", "Authorization: Bearer " + acc,
                        "-H", "Content-Type: application/json",
                        "--data-binary", "@" + reqFile));
                if (up.httpCode == 200) {
                    Log.info("  calendar ~ updated: " + e.title);
                    upd++;
                } else {
                    Util.writeFile(cfg.home.resolve("google_cal_error.json"), up.text());
                    Log.info("  calendar ! update failed (" + up.httpCode + "): " + e.title);
                    err++;
                }
            } else {
                Util.writeFile(cfg.home.resolve("google_cal_error.json"), ins.text());
                Log.info("  calendar ! insert failed (" + ins.httpCode + "): " + e.title + "  [google_cal_error.json]");
                err++;
            }
        }
        summary = "Calendar: " + ok + " created, " + upd + " updated, " + err + " failed, " + skip + " skipped.";
        Log.info(summary);
        ledger.record(events);
    }

    // ---- date helpers -------------------------------------------------------
    private static String datePlusDays(String ymd, int n) {
        try { return LocalDate.parse(ymd).plusDays(n).toString(); }
        catch (Exception e) { return ymd; }
    }
    private static String isoShiftHours(String iso, int n) {
        try {
            OffsetDateTime t = OffsetDateTime.parse(iso).plusHours(n);
            return t.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (Exception e) { return iso; }
    }
}
