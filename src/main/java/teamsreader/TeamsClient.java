package teamsreader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Reads the consumer Teams chat service over curl-impersonate. */
public final class TeamsClient {
    private final Config cfg;
    private final Curl curl;
    private final MsAuth auth;

    public TeamsClient(Config cfg, Curl curl, MsAuth auth) {
        this.cfg = cfg; this.curl = curl; this.auth = auth;
    }

    public static final class ImageData {
        public final String mime; public final String b64;
        ImageData(String mime, String b64) { this.mime = mime; this.b64 = b64; }
    }

    private List<String> baseHeaders(String url) {
        return new ArrayList<>(Arrays.asList(
            url, "-s", "--compressed",
            "--impersonate", cfg.impersonate,
            "-H", "authentication: skypetoken=" + auth.skypeToken,
            "-H", "ms-ic3-product: tfl",
            "-H", "ms-ic3-additional-product: Sfl",
            "-H", "behavioroverride: redirectAs404"));
    }

    /** Auto-discover all conversations (used only if the watch list is empty). */
    public List<String> listThreads() {
        String url = cfg.chatBase + "/users/ME/conversations"
                + "?view=msnp24Equivalent|supportsExtendedHistory&pageSize=50&startTime=1";
        String resp = curl.text(baseHeaders(url));
        List<String> out = new ArrayList<>();
        try {
            JSONArray convs = new JSONObject(resp).optJSONArray("conversations");
            if (convs != null) {
                for (int i = 0; i < convs.length(); i++) {
                    String id = convs.getJSONObject(i).optString("id", "");
                    if (id.startsWith("19:") && id.contains("@thread") && !out.contains(id)) out.add(id);
                }
            }
        } catch (Exception e) {
            Log.info("No conversations parsed. Raw saved to teams_lasterror.json");
            Util.writeFile(cfg.home.resolve("teams_lasterror.json"), resp);
        }
        Log.info("Discovered " + out.size() + " conversations.");
        return out;
    }

    /**
     * Diagnostic (--list-chats): print every conversation with a human-readable
     * label (kind, topic, last sender + snippet) so the user can pick the ID of a
     * chat to add to teams_watch.txt. The raw JSON is also saved for inspection.
     */
    public void dumpConversations() {
        String url = cfg.chatBase + "/users/ME/conversations"
                + "?view=msnp24Equivalent|supportsExtendedHistory&pageSize=50&startTime=1";
        String resp = curl.text(baseHeaders(url));
        Util.writeFile(cfg.home.resolve("teams_conversations.json"), resp);
        try {
            JSONArray convs = new JSONObject(resp).optJSONArray("conversations");
            if (convs == null) {
                Log.info("No 'conversations' array returned; raw saved to teams_conversations.json");
                return;
            }
            Log.info("Found " + convs.length() + " conversation(s):");
            for (int i = 0; i < convs.length(); i++) {
                JSONObject c = convs.getJSONObject(i);
                String id = c.optString("id", "");
                if (id.isEmpty()) continue;
                String kind = id.contains("@thread") ? "group" : "1:1  ";
                String topic = "";
                JSONObject tp = c.optJSONObject("threadProperties");
                if (tp != null) topic = tp.optString("topic", "");
                String last = "";
                JSONObject lm = c.optJSONObject("lastMessage");
                if (lm != null) {
                    String who = lm.optString("imdisplayname", "");
                    String content = Util.cleanForModel(lm.optString("content", ""));
                    if (content.length() > 60) content = content.substring(0, 60) + "...";
                    last = (who.isEmpty() ? "" : who + ": ") + content;
                }
                Log.info("  [" + kind + "] " + id
                        + (topic.isEmpty() ? "" : "  topic=\"" + topic + "\"")
                        + (last.isEmpty() ? "" : "  last=> " + last));
            }
            Log.info("---");
            Log.info("To have the bot read one of these, add its ID to ~/teamsreader/teams_watch.txt");
            Log.info("(one ID per line). Raw JSON: " + cfg.home.resolve("teams_conversations.json"));
        } catch (Exception e) {
            Log.info("Could not parse conversations: " + e.getMessage()
                    + " (raw saved to teams_conversations.json)");
        }
    }

    public String getTopic(String thread) {
        String url = cfg.chatBase + "/users/ME/conversations/" + Util.urlEncodePath(thread);
        String resp = curl.text(baseHeaders(url));
        String topic = Util.jsonStr(resp, "topic");
        return topic.isEmpty() ? thread : topic;
    }

    /** Fetch the recent message objects for a thread (newest history page). */
    public List<JSONObject> fetchMessages(String thread) {
        String url = cfg.chatBase + "/users/ME/conversations/" + Util.urlEncodePath(thread) + "/messages"
                + "?view=msnp24Equivalent|supportsMessageProperties|supportsExtendedHistory"
                + "&pageSize=" + cfg.exportPage + "&startTime=1";
        List<String> args = baseHeaders(url);
        args.add("-H"); args.add("clientinfo: os=windows; clientName=skypeteams; clientVer=1415/26110522541");

        String resp = curl.text(args);
        List<JSONObject> out = new ArrayList<>();
        try {
            JSONArray msgs = new JSONObject(resp).optJSONArray("messages");
            if (msgs != null) for (int i = 0; i < msgs.length(); i++) out.add(msgs.getJSONObject(i));
        } catch (Exception e) {
            Log.info("Could not parse messages for " + thread + "; raw saved to teams_lasterror.json");
            Util.writeFile(cfg.home.resolve("teams_lasterror.json"), resp);
        }
        return out;
    }

    /** Download a Teams/skype image and return {mime, base64}, or null. */
    public ImageData fetchImageB64(String url) {
        if (url == null || !url.startsWith("http")) return null;
        List<String> args = Arrays.asList(
            url, "-s", "--impersonate", cfg.impersonate,
            "-H", "Authorization: skype_token " + auth.skypeToken);
        byte[] b = curl.run(args).body;
        if (b == null || b.length < 100) return null;
        String mime = sniffMime(b);
        String b64 = java.util.Base64.getEncoder().encodeToString(b);
        return new ImageData(mime, b64);
    }

    /** Post a plain-text message (newlines -> <br>) to a conversation. True on 201. */
    public boolean postMessage(String thread, String text) {
        if (thread == null || thread.isEmpty() || text == null || text.isEmpty()) return false;
        String html = Util.htmlEsc(text).replace("\n", "<br>");
        String nonce = System.currentTimeMillis() + String.format("%06d", (int) (Math.random() * 1_000_000));
        JSONObject body = new JSONObject();
        body.put("content", html);
        body.put("messagetype", "RichText/Html");
        body.put("contenttype", "text");
        body.put("clientmessageid", nonce);

        Path reqFile = cfg.home.resolve("_tlog_req.json");
        Util.writeFile(reqFile, body.toString());

        String url = cfg.chatBase + "/users/ME/conversations/" + Util.urlEncodePath(thread) + "/messages";
        List<String> args = new ArrayList<>(Arrays.asList(
            url, "-s", "--compressed", "--impersonate", cfg.impersonate, "-X", "POST",
            "-H", "authentication: skypetoken=" + auth.skypeToken,
            "-H", "ms-ic3-product: tfl",
            "-H", "ms-ic3-additional-product: Sfl",
            "-H", "behavioroverride: redirectAs404",
            "-H", "content-type: application/json",
            "--data-binary", "@" + reqFile));
        Curl.Result r = curl.run(args);
        boolean ok = r.httpCode == 201;
        if (!ok) Log.info("  ! Teams log post failed (http " + r.httpCode + ")");
        return ok;
    }

    // Identify an image by magic bytes (port of _SniffMime).
    private static String sniffMime(byte[] b) {
        if (b.length < 12) return "image/unknown";
        int b0 = b[0] & 0xff, b1 = b[1] & 0xff, b2 = b[2] & 0xff, b3 = b[3] & 0xff;
        if (b0 == 0xFF && b1 == 0xD8) return "image/jpeg";
        if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) return "image/png";
        if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46) return "image/gif";
        if (b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46) { // RIFF
            if (eq(b, 8, "WEBP")) return "image/webp";
        }
        if (eq(b, 4, "ftyp")) {
            String brand = new String(b, 8, 4, StandardCharsets.US_ASCII).toLowerCase();
            if (Arrays.asList("heic","heix","heif","mif1","msf1","hevc","hevx").contains(brand))
                return "image/heic";
        }
        return "image/unknown";
    }
    private static boolean eq(byte[] b, int off, String ascii) {
        if (off + ascii.length() > b.length) return false;
        for (int i = 0; i < ascii.length(); i++) if ((b[off + i] & 0xff) != ascii.charAt(i)) return false;
        return true;
    }
}
