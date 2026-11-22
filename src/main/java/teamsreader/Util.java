package teamsreader;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** File I/O, Teams-HTML cleaning, JWT decoding - the AutoIt helper grab-bag. */
public final class Util {
    private Util() {}

    // ---- files --------------------------------------------------------------
    public static String readFile(Path p) {
        try {
            if (!Files.exists(p)) return "";
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) { return ""; }
    }

    public static boolean writeFile(Path p, String data) {
        try { Files.writeString(p, data, StandardCharsets.UTF_8); return true; }
        catch (IOException e) { Log.info("write failed " + p + ": " + e.getMessage()); return false; }
    }

    public static void backup(Path p) {
        try {
            if (Files.exists(p)) Files.copy(p, p.resolveSibling(p.getFileName() + ".bak"),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) { }
    }

    // ---- Teams message HTML cleaning ----------------------------------------
    // NB: message "content" is parsed with org.json, so it is ALREADY unescaped
    // (no literal \" or \/); these regexes therefore see real quotes/tags.

    private static final Pattern IMG   = Pattern.compile("(?i)<img\\b[^>]*?\\bsrc=\"([^\"]*)\"[^>]*>");
    private static final Pattern ANCH  = Pattern.compile("(?is)<a\\b[^>]*?\\bhref=\"([^\"]*)\"[^>]*>(.*?)</a>");
    private static final Pattern URIOBJ= Pattern.compile("(?i)<URIObject\\b[^>]*\\bOriginalName=\"([^\"]*)\"[^>]*>");
    private static final Pattern TAGS  = Pattern.compile("<[^>]+>");
    private static final Pattern WS    = Pattern.compile("[ \\t]+");

    /** _Clean: strip HTML, keep media markers / link text, decode entities. */
    public static String clean(String s) {
        if (s == null) return "";
        s = s.replace("\n", " ").replace("\r", " ").replace("\t", " ");
        s = IMG.matcher(s).replaceAll("[image: $1]");
        s = ANCH.matcher(s).replaceAll("$2 ($1)");
        s = URIOBJ.matcher(s).replaceAll("[file: $1]");
        s = TAGS.matcher(s).replaceAll("");
        s = decodeEntities(s);
        s = WS.matcher(s).replaceAll(" ");
        return s.trim();
    }

    /** _CleanForModel: clean, then drop the [image: ...] markers (images are
     *  sent to Gemini separately as inline parts). */
    public static String cleanForModel(String content) {
        String s = clean(content);
        s = s.replaceAll("\\[image:[^\\]]*\\]", "");
        s = WS.matcher(s).replaceAll(" ");
        return s.trim();
    }

    public static List<String> extractImgUrls(String content) {
        List<String> out = new ArrayList<>();
        if (content == null) return out;
        Matcher m = IMG.matcher(content);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    public static String decodeEntities(String s) {
        s = s.replace("&nbsp;", " ");
        s = s.replace("&#39;", "'");
        s = s.replace("&apos;", "'");
        s = s.replace("&quot;", "\"");
        s = s.replace("&lt;", "<");
        s = s.replace("&gt;", ">");
        s = s.replace("&amp;", "&");   // last, so &amp;nbsp; etc. resolve
        return s;
    }

    public static String htmlEsc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ---- JWT ----------------------------------------------------------------
    /** "exp" claim (epoch seconds) from a JWT, or 0. */
    public static long jwtExp(String jwt) {
        JSONObject payload = jwtPayload(jwt);
        return payload != null ? payload.optLong("exp", 0) : 0;
    }

    /** A string claim from a JWT payload, or "". */
    public static String jwtClaim(String jwt, String key) {
        JSONObject payload = jwtPayload(jwt);
        return payload != null ? payload.optString(key, "") : "";
    }

    private static JSONObject jwtPayload(String jwt) {
        if (jwt == null) return null;
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) return null;
        try {
            byte[] b = Base64.getUrlDecoder().decode(pad(parts[1]));
            return new JSONObject(new String(b, StandardCharsets.UTF_8));
        } catch (Exception e) { return null; }
    }

    private static String pad(String s) {
        int mod = s.length() % 4;
        if (mod == 2) return s + "==";
        if (mod == 3) return s + "=";
        return s;
    }

    // ---- regex JSON extraction (for raw / possibly-malformed responses) -----
    /** _JsonStr: first string value for a key, JSON-unescaped. "" if absent. */
    public static String jsonStr(String json, String key) {
        if (json == null) return "";
        // Unrolled-loop form: scan runs of plain chars iteratively ([^"\\]*) and
        // recurse only per escape sequence, so long values (e.g. a 1800-char
        // access_token) don't blow the stack like the old (?:[^"\\]|\\.)* did.
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"").matcher(json);
        return m.find() ? jsonUnescape(m.group(1)) : "";
    }
    /** _JsonNum: first numeric value for a key as text. "" if absent. */
    public static String jsonNum(String json, String key) {
        if (json == null) return "";
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)").matcher(json);
        return m.find() ? m.group(1) : "";
    }
    public static String jsonUnescape(String s) {
        if (s == null) return "";
        return s.replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t")
                .replace("\\/", "/").replace("\\\"", "\"").replace("\\\\", "\\");
    }
    /** First JWT-looking token in a blob (eyJ...eyJ...). "" if none. */
    public static String firstJwt(String s) {
        if (s == null) return "";
        Matcher m = Pattern.compile("(eyJ[A-Za-z0-9_\\-]+\\.eyJ[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+)").matcher(s);
        return m.find() ? m.group(1) : "";
    }

    // ---- misc ---------------------------------------------------------------
    public static String urlEncodePath(String s) {   // encode : and @ like the browser
        return s.replace(":", "%3A").replace("@", "%40");
    }

    /** Deterministic Calendar event id from the dedupe_key (hex of UTF-8 bytes),
     *  matching _GEventId so ids are stable across the AutoIt and Java versions. */
    public static String eventId(String key, String fallback) {
        if (key == null || key.isEmpty()) key = fallback;
        StringBuilder sb = new StringBuilder("tr");
        for (byte b : key.toLowerCase().getBytes(StandardCharsets.UTF_8)) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
