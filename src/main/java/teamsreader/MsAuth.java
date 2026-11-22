package teamsreader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * Teams (MSA consumer) auth chain, all over curl-impersonate:
 *   1. refresh_token -> login.microsoftonline.com -> access_token (+ ROTATED refresh_token)
 *   2. access_token  -> teams.live.com authz/consumer -> skypetoken (JWT, ~8h)
 *
 * The refresh_token ROTATES on every use; the new value is saved immediately
 * (previous kept as .bak), exactly like the AutoIt version.
 */
public final class MsAuth {
    private final Config cfg;
    private final Curl curl;

    public String skypeToken = "";
    public long   skypeExp   = 0;   // epoch seconds
    public String myId       = "";  // "8:live:seangriffin"

    public MsAuth(Config cfg, Curl curl) { this.cfg = cfg; this.curl = curl; }

    /** True if we hold a valid skypetoken (more than 5 min of life left),
     *  refreshing the chain if needed. False => Teams source unavailable. */
    public boolean ensureSkypeToken() {
        if (!skypeToken.isEmpty() && Instant.now().getEpochSecond() < skypeExp - 300) return true;

        Log.info("Refreshing tokens...");
        // Prefer cookie-replay silent auth (Option B) when login cookies are present;
        // it is driven by the long-lived login session, not the 24h SPA refresh token.
        String access = cfg.hasLoginCookies() ? cookieSilentAccessToken() : refreshAccessToken();
        if (access.isEmpty()) return false;

        String skype = getSkypeToken(access);
        if (skype.isEmpty()) return false;

        skypeToken = skype;
        skypeExp = Util.jwtExp(skype);
        if (skypeExp == 0) skypeExp = Instant.now().getEpochSecond() + 3600;

        String sid = Util.jwtClaim(skype, "skypeid");   // e.g. live:seangriffin
        if (!sid.isEmpty()) myId = "8:" + sid;

        Log.info("Got skypetoken, valid until epoch " + skypeExp);
        return true;
    }

    /** Step 1: refresh_token grant -> access_token, rotating the refresh_token. */
    private String refreshAccessToken() {
        String rt = Util.readFile(cfg.refreshFile).strip();
        if (rt.isEmpty()) return "";

        // Redeem under the client that OWNS the token (AADSTS70000 otherwise).
        // The Teams web client is a SPA, so AAD needs an Origin header (AADSTS90023,
        // the redemption must look like a cross-origin request); mobile/public
        // clients (from --device-login) must NOT send it.
        String client = cfg.authClientId();
        boolean isSpa = client.equals(cfg.clientId);
        List<String> args = new ArrayList<>(Arrays.asList(
            cfg.tokenUrl, "-s", "--compressed", "-X", "POST",
            "--impersonate", cfg.impersonate));
        if (isSpa) { args.add("-H"); args.add("Origin: https://teams.live.com"); }
        args.add("--data-urlencode"); args.add("client_id=" + client);
        args.add("--data-urlencode"); args.add("scope=" + cfg.scope);
        args.add("--data-urlencode"); args.add("grant_type=refresh_token");
        args.add("--data-urlencode"); args.add("client_info=1");
        args.add("--data-urlencode"); args.add("refresh_token=" + rt);

        String resp = curl.text(args);
        String access = Util.jsonStr(resp, "access_token");
        String newRt  = Util.jsonStr(resp, "refresh_token");

        if (access.isEmpty()) {
            Log.info("Token refresh FAILED. Response saved to teams_lasterror.json");
            Util.writeFile(cfg.home.resolve("teams_lasterror.json"), resp);
            return "";
        }

        // Persist the rotated refresh_token BEFORE anything else can fail.
        if (!newRt.isEmpty() && !newRt.equals(rt)) {
            Util.backup(cfg.refreshFile);
            Util.writeFile(cfg.refreshFile, newRt);
            Log.info("Rotated refresh_token saved.");
        }
        return access;
    }

    // ===== Option B: cookie-replay silent auth ===============================
    // Replays the browser's prompt=none authorize flow using the stored login
    // cookies: GET login.microsoftonline.com/authorize -> 302 login.live.com ->
    // 302 teams.live.com/v2/authv2#code=... , then exchange the code for tokens.
    // The login cookies outlive the 24h SPA refresh token by weeks/months.

    private String cookieSilentAccessToken() {
        try {
            String verifier  = pkceVerifier();
            String challenge = pkceChallenge(verifier);
            String nonce = java.util.UUID.randomUUID().toString();
            String state = java.util.UUID.randomUUID().toString();
            String reqId = java.util.UUID.randomUUID().toString();
            String redirect = cfg.teamsRedirectUri;
            String hint = cfg.loginHint();   // account email; required for prompt=none to resolve the account

            String url = cfg.liveAuthorizeUrl
                + "?client_id=" + enc(cfg.clientId)
                + "&scope=" + enc(cfg.scope)
                + "&redirect_uri=" + enc(redirect)
                + "&response_type=code&response_mode=fragment&prompt=none&client_info=1"
                + "&client-request-id=" + enc(reqId)
                + "&nonce=" + enc(nonce)
                + "&state=" + enc(state)
                + "&code_challenge=" + enc(challenge)
                + "&code_challenge_method=S256"
                + (hint.isEmpty() ? "" : "&login_hint=" + enc(hint))
                + "&x-client-SKU=msal.js.browser&x-client-VER=5.6.3";
            if (hint.isEmpty())
                Log.info("Note: teams_login_hint.dat is empty - prompt=none usually needs the account email as login_hint.");

            // Follow the redirect chain by hand (cookies differ per host) until we
            // land on the redirect_uri, which carries the auth code in its fragment.
            String code = "";
            for (int hop = 0; hop < 6 && code.isEmpty(); hop++) {
                String host = hostOf(url);
                String cookie = cookieForHost(host);
                List<String> args = new ArrayList<>(Arrays.asList(
                    url, "-s", "-i", "--impersonate", cfg.impersonate));
                if (!cookie.isEmpty()) { args.add("-H"); args.add("Cookie: " + cookie); }
                // Make the request look like the browser's cross-site iframe silent
                // auth; MSA refuses silent SSO (login_required) for bare requests.
                args.add("-H"); args.add("Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                args.add("-H"); args.add("Accept-Language: en-US,en;q=0.9");
                args.add("-H"); args.add("Upgrade-Insecure-Requests: 1");
                args.add("-H"); args.add("Sec-Fetch-Dest: iframe");
                args.add("-H"); args.add("Sec-Fetch-Mode: navigate");
                args.add("-H"); args.add("Sec-Fetch-Site: cross-site");
                args.add("-H"); args.add("Referer: https://teams.live.com/");
                String resp = curl.text(args);
                String loc = headerValue(resp, "location");
                if (loc.isEmpty()) {
                    Log.info("Silent authorize stalled at " + host + " (no redirect). Saved to teams_lasterror.json");
                    Util.writeFile(cfg.home.resolve("teams_lasterror.json"), resp);
                    return "";
                }
                if ("teams.live.com".equalsIgnoreCase(hostOf(loc))) {
                    String err = urlParam(loc, "error");
                    if (!err.isEmpty()) {
                        String desc = urlParam(loc, "error_description");
                        try { desc = java.net.URLDecoder.decode(desc, StandardCharsets.UTF_8); } catch (Exception ignore) {}
                        Log.info("Silent authorize returned '" + err + "'"
                                + (desc.isEmpty() ? "" : ": " + desc));
                        return "";
                    }
                    code = urlParam(loc, "code");
                    break;
                }
                url = loc;
            }
            if (code.isEmpty()) { Log.info("No auth code from the silent flow (redirect chain unexpected)."); return ""; }

            // Exchange the code for tokens (SPA client -> needs the Origin header).
            List<String> targs = Arrays.asList(
                cfg.tokenUrl, "-s", "--compressed", "-X", "POST",
                "--impersonate", cfg.impersonate,
                "-H", "Origin: https://teams.live.com",
                "--data-urlencode", "client_id=" + cfg.clientId,
                "--data-urlencode", "scope=" + cfg.scope,
                "--data-urlencode", "grant_type=authorization_code",
                "--data-urlencode", "code=" + code,
                "--data-urlencode", "redirect_uri=" + redirect,
                "--data-urlencode", "code_verifier=" + verifier,
                "--data-urlencode", "client_info=1");
            String resp = curl.text(targs);
            String access = Util.jsonStr(resp, "access_token");
            if (access.isEmpty()) {
                Log.info("Code->token exchange FAILED. Response saved to teams_lasterror.json");
                Util.writeFile(cfg.home.resolve("teams_lasterror.json"), resp);
            } else {
                Log.info("Silent cookie auth OK - minted a fresh access token.");
            }
            return access;
        } catch (Exception e) {
            Log.info("Cookie-silent auth error: " + e.getMessage());
            return "";
        }
    }

    private String cookieForHost(String host) {
        if (host == null) return "";
        if (host.contains("login.microsoftonline.com")) return readCookie(cfg.cookieMsOnlineFile);
        if (host.contains("live.com")) return readCookie(cfg.cookieLiveFile);
        return "";
    }
    private static String readCookie(java.nio.file.Path p) {
        return Files.exists(p) ? Util.readFile(p).strip() : "";
    }
    private static String hostOf(String url) {
        if (url == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^https?://([^/?#]+)").matcher(url);
        return m.find() ? m.group(1) : "";
    }
    private static String headerValue(String resp, String name) {
        if (resp == null) return "";
        for (String line : resp.split("\r?\n")) {
            int c = line.indexOf(':');
            if (c <= 0) continue;
            if (line.substring(0, c).trim().equalsIgnoreCase(name)) return line.substring(c + 1).trim();
        }
        return "";
    }
    /** Raw value of a query (?k=v) or fragment (#k=v) parameter - not decoded,
     *  so the auth code passes through verbatim to --data-urlencode. */
    private static String urlParam(String url, String key) {
        if (url == null) return "";
        int h = url.indexOf('#'), q = url.indexOf('?');
        String blob = h >= 0 ? url.substring(h + 1) : (q >= 0 ? url.substring(q + 1) : "");
        for (String kv : blob.split("&")) {
            int e = kv.indexOf('=');
            String k = e < 0 ? kv : kv.substring(0, e);
            if (k.equals(key)) return e < 0 ? "" : kv.substring(e + 1);
        }
        return "";
    }
    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
    private static String pkceVerifier() {
        byte[] b = new byte[32];
        new java.security.SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
    private static String pkceChallenge(String verifier) throws Exception {
        byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
    }

    /** Step 2: Bearer access_token -> skypetoken. */
    private String getSkypeToken(String access) {
        List<String> args = Arrays.asList(
            cfg.authzUrl, "-s", "--compressed", "-X", "POST",
            "--impersonate", cfg.impersonate,
            "-H", "authorization: Bearer " + access,
            "-H", "ms-teams-authz-type: ExplicitLogin",
            "-H", "origin: https://teams.live.com",
            "-H", "x-ms-client-type: web",
            "-H", "x-ms-client-version: 1415/26101516645",
            "-H", "content-length: 0");

        String resp = curl.text(args);
        String skype = extractSkypeToken(resp);
        if (skype.isEmpty()) {
            Log.info("authz/consumer FAILED. Response saved to teams_lasterror.json");
            Util.writeFile(cfg.home.resolve("teams_lasterror.json"), resp);
        }
        return skype;
    }

    /**
     * Microsoft Authentication Broker - a first-party *mobile* (public) client that
     * is a member of the FOCI token family. Device-code flow works for consumer
     * accounts with it, and the refresh token it returns is a long-lived (~90-day,
     * non-SPA) FAMILY token that can be redeemed for the Teams scope. Overridable
     * via the 2nd arg to --device-login so other family clients can be tried.
     */
    private static final String DEFAULT_DEVICE_CLIENT = "29d9ed98-a469-4536-ade2-f981bc1d605e";

    /**
     * Interactive re-seed via the OAuth 2.0 device-code flow: prints a short code +
     * URL for the user to complete on any device, polls for the tokens, saves the
     * (FOCI) refresh_token, then validates it by running the normal Teams chain.
     * Returns true if a skypetoken was obtained.
     */
    public boolean deviceLogin(String clientOverride) {
        String client = (clientOverride != null && !clientOverride.isBlank())
                ? clientOverride.trim() : DEFAULT_DEVICE_CLIENT;
        Log.info("Device-code sign-in using client " + client);

        // 1) Request a device code, asking for the TEAMS scope directly. The token
        //    stays bound to THIS mobile client, so we later refresh it under the
        //    same client (no cross-client redemption, which AAD rejects/70000).
        List<String> dcArgs = Arrays.asList(
            cfg.deviceCodeUrl, "-s", "-X", "POST", "--impersonate", cfg.impersonate,
            "--data-urlencode", "client_id=" + client,
            "--data-urlencode", "scope=" + cfg.scope);
        String dcResp = curl.text(dcArgs);
        String deviceCode = Util.jsonStr(dcResp, "device_code");
        String userCode   = Util.jsonStr(dcResp, "user_code");
        String verifyUri  = Util.jsonStr(dcResp, "verification_uri");
        String message    = Util.jsonStr(dcResp, "message");
        if (deviceCode.isEmpty() || userCode.isEmpty()) {
            Log.info("Device-code request FAILED. Response saved to teams_lasterror.json");
            Util.writeFile(cfg.home.resolve("teams_lasterror.json"), dcResp);
            return false;
        }

        Log.info("");
        Log.info("==================== SIGN IN ====================");
        if (!message.isEmpty()) Log.info(message);
        else {
            Log.info("Open: " + (verifyUri.isEmpty() ? "https://www.microsoft.com/link" : verifyUri));
            Log.info("Enter code: " + userCode);
        }
        Log.info("Then wait here - polling for completion (up to 10 min)...");
        Log.info("================================================");

        // 2) Poll the token endpoint until the user finishes (or it expires).
        int interval = 5;
        long deadline = System.currentTimeMillis() + 10 * 60 * 1000L;
        String rt = "", foci = "";
        while (System.currentTimeMillis() < deadline) {
            try { Thread.sleep(interval * 1000L); } catch (InterruptedException ignore) {}
            List<String> pollArgs = Arrays.asList(
                cfg.tokenUrl, "-s", "-X", "POST", "--impersonate", cfg.impersonate,
                "--data-urlencode", "grant_type=urn:ietf:params:oauth:grant-type:device_code",
                "--data-urlencode", "client_id=" + client,
                "--data-urlencode", "device_code=" + deviceCode);
            String pr = curl.text(pollArgs);
            String err = Util.jsonStr(pr, "error");
            if (err.equals("authorization_pending")) continue;
            if (err.equals("slow_down")) { interval += 5; continue; }
            if (!err.isEmpty()) {
                Log.info("Sign-in FAILED: " + err + " - " + firstLine(Util.jsonStr(pr, "error_description")));
                Util.writeFile(cfg.home.resolve("teams_lasterror.json"), pr);
                return false;
            }
            rt   = Util.jsonStr(pr, "refresh_token");
            foci = Util.jsonStr(pr, "foci");
            break;
        }
        if (rt.isEmpty()) { Log.info("Timed out (or no refresh_token returned) waiting for sign-in."); return false; }

        Util.backup(cfg.refreshFile);
        Util.writeFile(cfg.refreshFile, rt);
        // Record which client owns this token so refreshAccessToken() redeems it
        // under the same client (not the SPA web client).
        Util.writeFile(cfg.authClientFile, client);
        Log.info("Signed in. Saved refresh_token bound to client " + client
                + (foci.equals("1") ? " (foci=1)" : ""));

        // 3) Validate: redeem the saved token (under this same client) for a skypetoken.
        Log.info("Validating against Teams (redeeming for " + cfg.scope.split(" ")[0] + ") ...");
        skypeToken = ""; skypeExp = 0;   // force a fresh chain
        boolean ok = ensureSkypeToken();
        if (ok) Log.info("SUCCESS - got a skypetoken. Normal runs will use this client/token now.");
        else {
            // Validation failed: don't leave normal runs pointed at a client whose
            // token can't reach Teams - revert to the default (SPA web) client.
            try { Files.deleteIfExists(cfg.authClientFile); } catch (java.io.IOException ignore) {}
            Log.info("This client did NOT yield a Teams token - see teams_lasterror.json. "
                    + "Reverted the redeem client; try another with:  ./run.sh --device-login <client_id>");
        }
        return ok;
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    private static String extractSkypeToken(String json) {
        for (String k : new String[]{"skypeToken", "skypetoken", "tokenValue", "token"}) {
            String v = Util.jsonStr(json, k);
            if (v.startsWith("eyJ")) return v;
        }
        return Util.firstJwt(json);
    }
}
