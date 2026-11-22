package teamsreader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TeamsReader - Java port (Raspberry Pi). One-shot event-extraction flow:
 *   refresh Teams tokens -> collect Teams messages + screenshots + IMAP emails
 *   -> ONE multimodal Gemini call -> parse consolidated events -> upsert into
 *   Google Calendar (idempotent by dedupe_key) -> update ledger + IMAP state.
 *
 * Teams is best-effort: if its refresh token is stale the run continues
 * email-only (the confirmed-working path).
 */
public final class Main {
    private final Config cfg = new Config();
    private final Curl curl = new Curl(cfg);
    private final TimeUtil time = new TimeUtil(cfg.zone);
    private final MsAuth auth = new MsAuth(cfg, curl);
    private final TeamsClient teams = new TeamsClient(cfg, curl, auth);
    private final IniState state = new IniState(cfg.stateFile);
    private final Ledger ledger = new Ledger(cfg);
    private final GeminiClient gemini = new GeminiClient(cfg, curl);
    private final CalendarClient calendar = new CalendarClient(cfg, curl, ledger);
    private final EmailClient email = new EmailClient(cfg, state, time);

    private static final Set<String> MODEL_IMAGE_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif");

    private String runSummary = "";

    public static void main(String[] args) {
        Log.init(new Config().logFile);   // logFile resolved the same way as the instance cfg
        new Main().run(args);
    }

    private void run(String[] args) {
        String arg0 = (args != null && args.length > 0) ? args[0] : "";
        boolean listChats   = arg0.equals("--list-chats");
        boolean deviceLogin = arg0.equals("--device-login");
        // --device-login may take an optional client_id override as the 2nd arg.
        String deviceClient = (deviceLogin && args.length > 1) ? args[1] : "";
        Log.info("=== TeamsReader (Java) starting ==="
                + (listChats ? "  [--list-chats]" : deviceLogin ? "  [--device-login]" : ""));

        // curl check: only warn if an explicit path was given and is missing.
        String c = cfg.curl;
        if ((c.contains("/") || c.contains("\\")) && !Files.exists(java.nio.file.Paths.get(c))) {
            Log.info("WARNING: curl-impersonate not found at: " + c
                    + " (set TEAMSREADER_CURL). Continuing - it may be on PATH.");
        }

        // Interactive re-seed: device-code sign-in -> saves a (FOCI) refresh token,
        // then validates it against the Teams chain. Needs no Gemini key or prior token.
        if (deviceLogin) {
            auth.deviceLogin(deviceClient);
            Log.info("=== TeamsReader run complete ===");
            return;
        }

        if (!Files.exists(cfg.geminiKeyFile)) {
            throw Log.fatal("No Gemini API key. Save a free key from https://aistudio.google.com/apikey into "
                    + cfg.geminiKeyFile);
        }

        // Teams auth is best-effort; email source stands alone if it fails.
        boolean teamsOk = Files.exists(cfg.refreshFile) && auth.ensureSkypeToken();
        if (!teamsOk) {
            Log.info("WARNING: Teams auth unavailable (refresh token stale/missing?) - running EMAIL-ONLY. "
                    + "Re-seed teams_refresh_token.dat from a fresh HAR to restore the Teams source.");
        }

        // Diagnostic mode: dump every conversation (id + label) so the user can find
        // the ID of a chat (e.g. a personal 1:1) to add to teams_watch.txt, then exit.
        if (listChats) {
            if (!teamsOk) { Log.info("Teams auth unavailable - cannot list chats. Re-seed the refresh token first."); return; }
            teams.dumpConversations();
            Log.info("=== TeamsReader run complete ===");
            return;
        }

        List<String> threads = new ArrayList<>(cfg.watchThreads());
        if (teamsOk && threads.isEmpty()) threads = teams.listThreads();

        if (teamsOk && cfg.logToTeams)
            teams.postMessage(cfg.logThread, "TeamsReader run started - " + time.teamsStamp(Instant.now()));

        extractEvents(threads, teamsOk);

        if (teamsOk && cfg.logToTeams)
            teams.postMessage(cfg.logThread, runSummary);

        Log.info("=== TeamsReader run complete ===");
    }

    private void extractEvents(List<String> threads, boolean teamsOk) {
        Instant now = Instant.now();
        Instant cutoff = now.minusSeconds((long) cfg.eventHours * 3600);
        String tz = time.tzLabel();
        String nowLoc = time.localStampSec(now) + " " + tz;

        String instr =
            "You extract real-world calendar EVENTS from a family's chats (group and 1:1).\n"
          + "The current local date and time is " + nowLoc + " (timezone " + tz + ").\n"
          + "Chat messages follow in chronological order, each prefixed [ref N] then\n"
          + "[local-time] Sender: text. The [ref N] is a stable id for that message.\n"
          + "Some messages posted a SCREENSHOT (e.g. a booking confirmation); the image is\n"
          + "attached inline right after a line that says it was posted, and often holds the\n"
          + "decisive detail (venue, address, date, time) in pixels, not text.\n\n"
          + "TASK:\n"
          + "1. Identify events the family plans to attend/do that have (or imply) a date/time.\n"
          + "2. CONSOLIDATE: messages that refine the SAME event (change of venue, time, or a\n"
          + "   final decision in a screenshot) are ONE event. Use the LATEST decided values;\n"
          + "   a later message or image SUPERSEDES an earlier one.\n"
          + "3. Resolve relative times ('10am', 'tomorrow', 'Sat') against the posting message's\n"
          + "   local timestamp. Output absolute local ISO 8601 with offset (e.g.\n"
          + "   2026-11-15T10:00:00" + tz + "). If only a date is known, set all_day=true.\n"
          + "4. NEVER invent or guess a specific calendar date. Only output a date you can ground\n"
          + "   in an EXPLICIT date in the text/image, or a relative term anchored to the posting\n"
          + "   timestamp ('tomorrow', 'next Friday'). References you cannot resolve to an actual\n"
          + "   day - e.g. a school-term week like 'Week 9', 'next term', 'later this month' - are\n"
          + "   NOT datable: do NOT output such an event (a later message or image often supplies\n"
          + "   the real date, and the dedupe_key will consolidate it then). A wrong date is worse\n"
          + "   than a missed one.\n"
          + "5. Ignore chit-chat, reactions and anything with no actionable date/time.\n\n"
          + "Return ONLY a JSON object: {\"events\":[ ... ]}. Each event object has keys:\n"
          + "  title (string), start (local ISO8601 or null), end (local ISO8601 or null),\n"
          + "  all_day (bool), location (string or ''), confidence (0..1 number),\n"
          + "  source (\"text\"|\"image\"|\"both\"), evidence (<=140 chars: why/what decided it),\n"
          + "  dedupe_key (stable lowercase slug identifying the event, reused across refinements),\n"
          + "  source_ref (the N from the [ref N] of the ONE chat message or screenshot that most\n"
          + "    decides this event - its date, venue, or final confirmation; pick the most decisive.\n"
          + "    Output just the number. Use null if the event came only from an EMAIL or forwarded TEXT).\n"
          + "If there are no events, return {\"events\":[]}.\n"
          + "Some entries are EMAILS or forwarded phone TEXT messages (prefixed 'EMAIL from' or\n"
          + "'forwarded TEXT from'; a forwarded text has " + cfg.smsTag + " in its subject). Treat\n"
          + "them as additional event sources - the same consolidation and relative-time rules apply.";

        String ledgerBlock = ledger.promptBlock();
        if (!ledgerBlock.isEmpty()) instr += "\n\n" + ledgerBlock;

        JSONArray parts = new JSONArray();
        parts.put(GeminiClient.textPart(instr));
        int[] used = {0, 0};   // {messages, images}
        Map<String, String> refToUrl = new HashMap<>();  // [ref N] -> Teams deep link
        int refCounter = 0;

        if (teamsOk) {
            for (String thread : threads) {
                if (thread == null || thread.isEmpty()) continue;
                String topic = teams.getTopic(thread);
                Log.info("Collecting '" + topic + "' (last " + cfg.eventHours + "h) for extraction...");

                List<JSONObject> msgs = teams.fetchMessages(thread);
                // window + sort oldest-first
                List<Object[]> rows = new ArrayList<>();
                for (JSONObject m : msgs) {
                    Instant t = TimeUtil.parseIso(m.optString("originalarrivaltime", ""));
                    if (t == null || t.isBefore(cutoff)) continue;
                    rows.add(new Object[]{ t, m });
                }
                if (rows.isEmpty()) continue;
                rows.sort(Comparator.comparing(a -> (Instant) a[0]));

                parts.put(GeminiClient.textPart("=== Chat: " + topic + " ==="));
                for (Object[] row : rows) {
                    Instant t = (Instant) row[0];
                    JSONObject m = (JSONObject) row[1];
                    String type = m.optString("messagetype", "");
                    if (!(type.startsWith("RichText") || type.equals("Text"))) continue;

                    String who = m.optString("imdisplayname", "");
                    if (who.isEmpty()) who = "Unknown";
                    String stamp = time.localStampMin(t);
                    String content = m.optString("content", "");
                    List<String> imgUrls = Util.extractImgUrls(content);
                    String text = Util.cleanForModel(content);

                    // Give this message a [ref N] the model can cite, mapped to its deep link.
                    String msgId = m.optString("id", "");
                    if (msgId.isEmpty()) msgId = Long.toString(t.toEpochMilli());
                    int ref = ++refCounter;
                    refToUrl.put(Integer.toString(ref), teamsMsgUrl(thread, msgId));
                    String tag = "[ref " + ref + "] ";

                    if (!text.isEmpty()) {
                        parts.put(GeminiClient.textPart(tag + "[" + stamp + " " + tz + "] " + who + ": " + text));
                        used[0]++;
                    }
                    for (String url : imgUrls) {
                        if (used[1] >= cfg.maxImages) break;
                        TeamsClient.ImageData img = teams.fetchImageB64(url);
                        if (img == null) { Log.info("  ! image download failed/too small, skipped"); continue; }
                        int bytes = img.b64.length() * 3 / 4;
                        if (!MODEL_IMAGE_TYPES.contains(img.mime)) {
                            Log.info("  ! image type " + img.mime + " (~" + bytes + "b) not model-supported, skipped");
                            continue;
                        }
                        Log.info("  + image " + img.mime + " (~" + bytes + "b) from " + who + " @ " + stamp);
                        parts.put(GeminiClient.textPart(tag + "[screenshot posted by " + who + " at " + stamp + " " + tz + "]:"));
                        parts.put(GeminiClient.imagePart(img.mime, img.b64));
                        used[1]++;
                    }
                }
            }
        }

        // ---- email source ----
        EmailClient.Result emailResult = null;
        if (cfg.readEmail) {
            emailResult = email.collect();
            if (!emailResult.entries.isEmpty()) {
                parts.put(GeminiClient.textPart("=== Email inbox (" + cfg.imapUser + ") ==="));
                for (String entry : emailResult.entries) parts.put(GeminiClient.textPart(entry));
                used[0] += emailResult.entries.size();
            }
        }

        if (used[0] == 0 && used[1] == 0) {
            Log.info("Nothing in the last " + cfg.eventHours + "h to extract.");
            writeEventsHtml(new ArrayList<>(), 0, 0);
            runSummary = "Run complete - " + time.teamsStamp(Instant.now()) + "\n"
                    + "Nothing in the last " + cfg.eventHours + "h to add.";
            return;
        }
        Log.info("Sending " + used[0] + " messages + " + used[1] + " screenshots to " + cfg.geminiModel + "...");

        String modelJson = gemini.generate(parts);
        Util.writeFile(cfg.eventsJson, modelJson);

        List<Event> events = parseEvents(modelJson);
        // Resolve each event's [ref N] back to the Teams message deep link.
        for (Event e : events) {
            String r = e.sourceRef == null ? "" : e.sourceRef.trim().replaceAll("[^0-9]", "");
            if (!r.isEmpty()) {
                String u = refToUrl.get(r);
                if (u != null) e.sourceUrl = u;
            }
        }
        renderEvents(events, used[0], used[1]);

        if (cfg.pushCalendar) calendar.push(events);

        // ---- email housekeeping: advance high-water, sweep forwarded texts ----
        if (cfg.readEmail && emailResult != null) {
            if (emailResult.maxUid > 0) state.write("email", "lastuid", Long.toString(emailResult.maxUid));
            if (!emailResult.smsUids.isEmpty()) email.sweep(emailResult.smsUids);
        }

        // ---- run summary (posted to the Teams heartbeat) ----
        StringBuilder sum = new StringBuilder("Run complete - " + time.teamsStamp(Instant.now()) + "\n"
                + "Scanned " + used[0] + " messages + " + used[1] + " screenshots.\n"
                + "Events found: " + events.size());
        for (Event e : events) sum.append("\n - ").append(e.title).append(e.hasStart() ? "  " + e.start : "");
        if (!calendar.summary.isEmpty()) sum.append("\n").append(calendar.summary);
        runSummary = sum.toString();
    }

    /** Build the "copy link" deep link for a Teams message (thread id + message id). */
    private static String teamsMsgUrl(String thread, String msgId) {
        return "https://teams.live.com/l/message/" + thread + "/" + msgId
             + "?context=%7B%22contextType%22%3A%22chat%22%7D";
    }

    private List<Event> parseEvents(String modelJson) {
        List<Event> out = new ArrayList<>();
        // responseMimeType=application/json should prevent fences, but strip any just in case.
        String json = modelJson.strip();
        if (json.startsWith("```")) {
            json = json.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("\\s*```$", "").strip();
        }
        try {
            JSONArray arr = new JSONObject(json).optJSONArray("events");
            if (arr != null) for (int i = 0; i < arr.length(); i++) out.add(new Event(arr.getJSONObject(i)));
        } catch (Exception e) {
            Log.info("Could not parse events JSON from the model: " + e.getMessage());
        }
        return out;
    }

    private void renderEvents(List<Event> events, int msgs, int imgs) {
        Log.info("Extracted " + events.size() + " event(s).");
        for (Event e : events) {
            String when = !e.hasStart() ? "(time unknown)" : e.start;
            if (e.allDay) when = e.start.substring(0, Math.min(10, e.start.length())) + " (all day)";
            else if (e.hasEnd()) when += "  to  " + e.end;
            Log.info("  * " + e.title + "  |  " + when
                    + (!e.location.isEmpty() ? "  @ " + e.location : "")
                    + "  [" + e.source + ", conf " + e.confidence + "]");
        }
        writeEventsHtml(events, msgs, imgs);
        Log.info("Events JSON:  " + cfg.eventsJson);
        Log.info("Events view:  " + cfg.eventsHtml);
    }

    /** Compact readable card view (teams_events.html). */
    private void writeEventsHtml(List<Event> events, int msgs, int imgs) {
        StringBuilder b = new StringBuilder();
        b.append("<!doctype html><html><head><meta charset=\"utf-8\">")
         .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
         .append("<title>Events</title><style>")
         .append("body{margin:0;background:#f0f0f0;font-family:'Segoe UI',system-ui,Arial,sans-serif;color:#242424}")
         .append(".wrap{max-width:720px;margin:0 auto;padding:16px}")
         .append(".hdr{font-size:12px;color:#616161;text-align:center;padding:8px;margin-bottom:8px}")
         .append(".empty{text-align:center;color:#616161;padding:40px}")
         .append(".ev{background:#fff;border-radius:12px;padding:14px 16px;margin-bottom:12px;box-shadow:0 1px 3px rgba(0,0,0,.08);border-left:4px solid #6264a7}")
         .append(".ev-title{font-weight:600;font-size:17px}.ev-when{color:#444;font-size:14px;margin-top:4px}")
         .append(".ev-loc{color:#444;font-size:14px;margin-top:2px}.ev-evid{color:#616161;font-size:12px;font-style:italic;margin-top:6px}")
         .append(".ev-src{color:#9a9a9a;font-size:11px;margin-top:6px}")
         .append("</style></head><body><div class=\"wrap\">")
         .append("<div class=\"hdr\">Events found &middot; ").append(events.size())
         .append(" from ").append(msgs).append(" messages + ").append(imgs).append(" screenshots &middot; ")
         .append(cfg.geminiModel).append(" &middot; ").append(time.localStampSec(Instant.now())).append("</div>");
        if (events.isEmpty()) b.append("<div class=\"empty\">No events detected in this window.</div>");
        for (Event e : events) {
            String when = !e.hasStart() ? "(time unknown)" : e.start;
            if (e.allDay) when = e.start.substring(0, Math.min(10, e.start.length())) + " (all day)";
            else if (e.hasEnd()) when += "  to  " + e.end;
            b.append("<div class=\"ev\"><div class=\"ev-title\">").append(Util.htmlEsc(e.title)).append("</div>")
             .append("<div class=\"ev-when\">").append(Util.htmlEsc(when)).append("</div>");
            if (!e.location.isEmpty()) b.append("<div class=\"ev-loc\">&#128205; ").append(Util.htmlEsc(e.location)).append("</div>");
            if (!e.evidence.isEmpty()) b.append("<div class=\"ev-evid\">").append(Util.htmlEsc(e.evidence)).append("</div>");
            b.append("<div class=\"ev-src\">source: ").append(Util.htmlEsc(e.source)).append("</div></div>");
        }
        b.append("</div></body></html>");
        Util.writeFile(cfg.eventsHtml, b.toString());
    }
}
