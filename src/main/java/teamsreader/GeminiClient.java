package teamsreader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * The single multimodal Gemini call. Builds one request from all the text parts
 * (Teams messages + emails) and inline image parts (screenshots), POSTs it, and
 * returns the model's raw JSON ({"events":[...]}) text.
 *
 * <p>No --impersonate here: this is Google's own API (nothing to evade), and the
 * browser TLS/HTTP-2 fingerprint can stall large POSTs. We force HTTP/1.1 and cap
 * the time, exactly like the AutoIt version. thinkingBudget:0 disables the slow
 * thinking pass; responseMimeType forces raw JSON (no code fences).
 */
public final class GeminiClient {
    private final Config cfg;
    private final Curl curl;

    public GeminiClient(Config cfg, Curl curl) { this.cfg = cfg; this.curl = curl; }

    public static JSONObject textPart(String text) {
        return new JSONObject().put("text", text);
    }
    public static JSONObject imagePart(String mime, String b64) {
        return new JSONObject().put("inline_data",
                new JSONObject().put("mime_type", mime).put("data", b64));
    }

    /** POST the assembled parts; return the model's JSON text, or "" on failure. */
    public String generate(JSONArray parts) {
        String key = Util.readFile(cfg.geminiKeyFile).strip();
        if (key.isEmpty()) throw Log.fatal("Gemini key file is empty: " + cfg.geminiKeyFile);

        JSONObject body = new JSONObject();
        body.put("contents", new JSONArray().put(
                new JSONObject().put("role", "user").put("parts", parts)));
        body.put("generationConfig", new JSONObject()
                .put("temperature", 0)
                .put("responseMimeType", "application/json")
                .put("thinkingConfig", new JSONObject().put("thinkingBudget", 0)));

        Path reqFile = cfg.home.resolve("_gemini_req.json");
        Util.writeFile(reqFile, body.toString());

        String api = cfg.geminiApiBase + cfg.geminiModel + ":generateContent?key=" + key;
        List<String> args = Arrays.asList(
                api, "-s", "--compressed", "-X", "POST", "--http1.1", "--max-time", "180",
                "-H", "Content-Type: application/json",
                "--data-binary", "@" + reqFile);

        String resp = "", modelJson = "";
        for (int attempt = 1; attempt <= 4; attempt++) {
            resp = curl.text(args);
            modelJson = candidateText(resp);
            if (!modelJson.isEmpty()) break;
            boolean transient_ = resp.isEmpty() || resp.contains("UNAVAILABLE")
                    || resp.contains("high demand") || resp.contains("overloaded")
                    || resp.contains("\"code\": 503");
            if (attempt < 4 && transient_) {
                Log.info("  Gemini busy (attempt " + attempt + "/4), retrying in " + (attempt * 5) + "s...");
                try { Thread.sleep(attempt * 5000L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                continue;
            }
            break;   // non-transient error - stop and report
        }

        if (modelJson.isEmpty()) {
            Util.writeFile(cfg.home.resolve("teams_gemini_error.json"), resp);
            throw Log.fatal("Gemini returned no usable content. Raw response saved to teams_gemini_error.json");
        }
        return modelJson;
    }

    /** Pull candidates[0].content.parts[*].text out of the API envelope. */
    private static String candidateText(String resp) {
        if (resp == null || resp.isEmpty()) return "";
        try {
            JSONArray cands = new JSONObject(resp).optJSONArray("candidates");
            if (cands != null && cands.length() > 0) {
                JSONObject content = cands.getJSONObject(0).optJSONObject("content");
                if (content != null) {
                    JSONArray parts = content.optJSONArray("parts");
                    if (parts != null) {
                        for (int i = 0; i < parts.length(); i++) {
                            String t = parts.getJSONObject(i).optString("text", "");
                            if (!t.isEmpty()) return t;
                        }
                    }
                }
            }
        } catch (Exception ignored) { /* fall through to regex */ }
        // regex fallback: first "text" after the first "content"
        int c = resp.indexOf("\"content\"");
        if (c < 0) return "";
        return Util.jsonStr(resp.substring(c), "text");
    }
}
