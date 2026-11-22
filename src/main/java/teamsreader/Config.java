package teamsreader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * All the tunables that were {@code Global $g_...} in TeamsReader.au3.
 *
 * <p>Paths resolve against the "home" directory, which is where the secret /
 * state files live. By default that is the current working directory (so on the
 * Pi you run the jar from the folder holding the .dat files). Override with the
 * {@code TEAMSREADER_HOME} environment variable.
 *
 * <p>curl-impersonate location and TLS profile come from the environment too, so
 * the same jar runs on Windows and the Pi without edits:
 * <ul>
 *   <li>{@code TEAMSREADER_CURL}        - path to the curl-impersonate binary</li>
 *   <li>{@code TEAMSREADER_IMPERSONATE} - TLS/JA3 profile (e.g. chrome136)</li>
 * </ul>
 */
public final class Config {

    // ---- home / tools -------------------------------------------------------
    public final Path home;
    public final String curl;          // curl-impersonate binary
    public final String impersonate;   // TLS/JA3 profile, e.g. "chrome136"

    // ---- feature flags (one-shot extract mode mirrors $g_bExtractEvents) -----
    public final boolean extractEvents = true;
    public final boolean pushCalendar  = true;
    public final boolean readEmail     = true;
    public final boolean logToTeams    = false;  // run log now shown on the Pi dashboard (Calendar page); flip to true to also post it to the Teams notes-to-self stream

    // ---- windows / caps -----------------------------------------------------
    public final int eventHours = 48;   // look-back window for extraction
    public final int maxImages  = 20;   // cap screenshots sent in one call
    public final int maxEmails  = 25;   // cap emails scanned per run
    public final int exportPage = 200;  // Teams messages fetched per thread
    public final int ledgerDays = 60;   // forget ledger entries older than this

    // ---- state / secret files ----------------------------------------------
    public final Path refreshFile;      // teams_refresh_token.dat  (ROTATES!)
    public final Path stateFile;        // teams_state.ini
    public final Path logFile;          // teams_reader.log
    public final Path geminiKeyFile;    // teams_gemini_key.dat
    public final Path eventsJson;       // teams_events.json
    public final Path eventsHtml;       // teams_events.html
    public final Path ledgerFile;       // teams_events_ledger.txt
    public final Path gClientFile;      // google_client.dat
    public final Path gRefreshFile;     // google_refresh_token.dat
    public final Path imapPassFile;     // teams_internode_imap.dat
    public final Path watchFile;        // teams_watch.txt - extra conversation IDs
    public final Path authClientFile;   // teams_auth_client.dat - client_id that owns the refresh token
    public final Path cookieMsOnlineFile; // teams_cookie_msonline.dat - login.microsoftonline.com cookie
    public final Path cookieLiveFile;     // teams_cookie_live.dat - login.live.com cookie
    public final Path loginHintFile;      // teams_login_hint.dat - account email for login_hint

    // ---- Teams (MSA consumer) constants, straight from the HAR ---------------
    public final String tenant   = "9188040d-6c67-4c5b-b112-36a304b66dad";
    public final String clientId = "4b3e8f46-56d3-427f-b1e2-d239b2ea6bca";
    public final String scope    = "https://auth.fl.teams.microsoft.com/teams.auth.readwrite openid profile offline_access";
    public final String tokenUrl = "https://login.microsoftonline.com/" + tenant + "/oauth2/v2.0/token";
    public final String deviceCodeUrl = "https://login.microsoftonline.com/" + tenant + "/oauth2/v2.0/devicecode";
    public final String authorizeUrl = "https://login.microsoftonline.com/" + tenant + "/oauth2/v2.0/authorize";
    public final String liveAuthorizeUrl = "https://login.live.com/oauth20_authorize.srf";  // MSA authority (direct, no microsoftonline hop)
    public final String teamsRedirectUri = "https://teams.live.com/v2/authv2";  // SPA redirect that catches the #code
    public final String authzUrl = "https://teams.live.com/api/auth/v2.0/authz/consumer";
    public final String chatBase = "https://teams.live.com/api/chatsvc/consumer/v1";

    // Threads to watch (the family COMMUNICATION!!! chat). Extra conversation IDs
    // can be added at runtime via teams_watch.txt (see watchThreads()), so a new
    // chat - e.g. a personal 1:1 - can be picked up without rebuilding the jar.
    public final String[] watch = { "19:297b57515cc5445a8274ec8faefbc8aa@thread.skype" };
    public final String logThread = "48:notes";   // Notes-to-self heartbeat stream

    // ---- Gemini -------------------------------------------------------------
    public final String geminiModel = "gemini-3.5-flash";
    public final String geminiApiBase = "https://generativelanguage.googleapis.com/v1beta/models/";

    // ---- Google Calendar ----------------------------------------------------
    public final String gScope    = "https://www.googleapis.com/auth/calendar.events";
    public final String gTokenUrl = "https://oauth2.googleapis.com/token";
    public final String gCalBase  = "https://www.googleapis.com/calendar/v3";
    public final String gCalId    = "primary";
    public final ZoneId zone      = ZoneId.of("Australia/Brisbane");  // IANA tz for timed events

    // ---- IMAP (internode via The Messaging Company) -------------------------
    public final String imapHost = "imap.themessagingco.com.au";
    public final int    imapPort = 993;
    public final String imapUser = "seangriffin@internode.on.net";
    public final String imapTrash = "Trash";
    public final String smsTag = "[SMS]";

    public Config() {
        String h = System.getenv("TEAMSREADER_HOME");
        this.home = (h != null && !h.isBlank()) ? Paths.get(h).toAbsolutePath()
                                                : Paths.get("").toAbsolutePath();

        String c = System.getenv("TEAMSREADER_CURL");
        if (c == null || c.isBlank()) {
            // Sensible default for the Pi; override via env on Windows.
            c = System.getProperty("os.name", "").toLowerCase().contains("win")
                    ? "curl-impersonate.exe"
                    : System.getProperty("user.home") + "/curl-impersonate-v0.6.0.arm-linux-gnueabihf/curl-impersonate";
        }
        this.curl = c;

        String imp = System.getenv("TEAMSREADER_IMPERSONATE");
        this.impersonate = (imp != null && !imp.isBlank()) ? imp : "chrome136";

        this.refreshFile   = home.resolve("teams_refresh_token.dat");
        this.stateFile     = home.resolve("teams_state.ini");
        this.logFile       = home.resolve("teams_reader.log");
        this.geminiKeyFile = home.resolve("teams_gemini_key.dat");
        this.eventsJson    = home.resolve("teams_events.json");
        this.eventsHtml    = home.resolve("teams_events.html");
        this.ledgerFile    = home.resolve("teams_events_ledger.txt");
        this.gClientFile   = home.resolve("google_client.dat");
        this.gRefreshFile  = home.resolve("google_refresh_token.dat");
        this.imapPassFile  = home.resolve("teams_internode_imap.dat");
        this.watchFile     = home.resolve("teams_watch.txt");
        this.authClientFile = home.resolve("teams_auth_client.dat");
        this.cookieMsOnlineFile = home.resolve("teams_cookie_msonline.dat");
        this.cookieLiveFile     = home.resolve("teams_cookie_live.dat");
        this.loginHintFile      = home.resolve("teams_login_hint.dat");
    }

    /** The account email used as login_hint on the silent authorize request, so
     *  prompt=none can resolve which account to sign in. Stored in a file (not
     *  source) to keep the address private. Empty if not set. */
    public String loginHint() {
        return Files.exists(loginHintFile) ? Util.readFile(loginHintFile).strip() : "";
    }

    /** True when the login.live.com cookie (the durable MSA session) is present ->
     *  use the cookie-replay silent-auth path (Option B) instead of the rotating
     *  refresh token. The login.microsoftonline.com cookie is an optional helper
     *  for the first hop and may be absent. */
    public boolean hasLoginCookies() {
        return Files.exists(cookieLiveFile);
    }

    /**
     * The client_id that OWNS the current refresh token and must be used to redeem
     * it. Defaults to the Teams web (SPA) client {@link #clientId}; --device-login
     * writes the mobile client's id here when it re-seeds via device-code flow, so
     * a refresh token must always be redeemed under the client that issued it
     * (AADSTS70000 otherwise). The web client also needs an Origin header on
     * redemption (it's a SPA); mobile/public clients do not.
     */
    public String authClientId() {
        String s = Files.exists(authClientFile) ? Util.readFile(authClientFile).strip() : "";
        return s.isEmpty() ? clientId : s;
    }

    /**
     * Conversation IDs to read: the hardcoded {@link #watch} list plus any extra
     * IDs listed in teams_watch.txt (one per line; blank lines and lines starting
     * with '#' are ignored). This lets a new chat be added by editing a text file
     * on the Pi - no rebuild required. Use {@code --list-chats} to discover IDs.
     */
    public List<String> watchThreads() {
        List<String> out = new ArrayList<>();
        for (String t : watch) {
            if (t != null && !t.isBlank() && !out.contains(t.trim())) out.add(t.trim());
        }
        try {
            if (Files.exists(watchFile)) {
                for (String line : Files.readAllLines(watchFile, StandardCharsets.UTF_8)) {
                    String s = line.trim();
                    if (s.isEmpty() || s.startsWith("#")) continue;
                    if (!out.contains(s)) out.add(s);
                }
            }
        } catch (IOException ignore) {}
        return out;
    }
}
