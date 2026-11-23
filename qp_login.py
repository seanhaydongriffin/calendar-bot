#!/usr/bin/env python3
# =============================================================================
# qp_login.py : Pi-only QParents login. WebKit2 mints the reCAPTCHA tokens,
# urllib does the HTTP (which is known to work - fetch inside WebKit fails).
#
# Flow:
#   - load saved cookies; GET /api/user. If 200 -> session still valid, done.
#   - else log in: POST /api/authentication/login {email,password,captchaToken}
#       200 -> trusted (MFA skipped)
#       420 -> POST again {..,mfaMedium:2,isNewDevice:false} (emails a code),
#              you type the code, POST {..,mfaSessionId,token:CODE} -> 200
#   - save cookies (session + PingIdTrustedDevice) so next run reuses them.
#   - GET /api/user -> print upcoming events.
#
# Run:  xvfb-run -a python3 qp_login.py        (interactive only when MFA needed)
# Creds: ~/teamsreader/qparents_cred.dat  as  email|password
# Each login POST needs a FRESH token; WebKit mints them on one loaded page.
# =============================================================================
import gi, os, sys, json, re, http.cookiejar, urllib.request, urllib.error, uuid
import time, imaplib, email, email.utils
gi.require_version("Gtk", "3.0"); gi.require_version("WebKit2", "4.0")
from gi.repository import Gtk, WebKit2, GLib

BASE      = "https://qparents.qld.edu.au"
LOGIN_URL = BASE + "/login"
KEY       = "6LeWiWwpAAAAAKStrU5enwrbXEGt9U3iFJTUut1m"
PROFILE   = os.path.expanduser("~/teamsreader/qp_webkit_profile")
CRED      = os.path.expanduser("~/teamsreader/qparents_cred.dat")
COOKIES   = os.path.expanduser("~/teamsreader/qparents_cookies.txt")
SESSION   = os.path.expanduser("~/teamsreader/qparents_session.dat")
NONINT    = "--non-interactive" in sys.argv   # cron mode: never prompt for an MFA code
os.makedirs(PROFILE, exist_ok=True)

if not os.path.exists(CRED):
    print("Missing", CRED, "(one line: email|password)"); sys.exit(1)
EMAIL, _, PW = open(CRED).read().strip().partition("|")
if not EMAIL or not PW:
    print("qparents_cred.dat must be  email|password"); sys.exit(1)

CLIENT = {
    "accept": "application/json", "content-type": "application/json",
    "Client-Platform": "web", "Client-App-Version": "Web",
    "Client-Uuid": str(uuid.uuid4()), "Client-Device": "Windows NT 10.0",
    "Client-OS-Version": "Windows NT 10.0; Win64; x64", "Origin": BASE,
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
}

jar = http.cookiejar.MozillaCookieJar(COOKIES)
if os.path.exists(COOKIES):
    try: jar.load(ignore_discard=True, ignore_expires=True)
    except Exception as e: print("[warn] cookie load:", e)
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))

def get_user():
    req = urllib.request.Request(BASE + "/api/user?IncludeFutureStudents=true",
          headers={k: v for k, v in CLIENT.items() if k != "content-type"})
    try:
        r = opener.open(req, timeout=25); return r.getcode(), r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")

def show_events(body):
    n = 0
    for m in re.finditer(r'"displayName":"([^"]*)"[^}]*?"startDate":"([^"]*)"', body):
        print("   EVENT:", m.group(1), "|", m.group(2)); n += 1
    print("   events matched:", n)

def write_session():
    """Write the QParents cookies as a ready-to-use Cookie header for the Java bot."""
    pairs = ["%s=%s" % (c.name, c.value) for c in jar if "qparents" in (c.domain or "")]
    try:
        with open(SESSION, "w") as f: f.write("; ".join(pairs))
        os.chmod(SESSION, 0o600)
        print("[*] wrote session (%d cookies) -> %s" % (len(pairs), SESSION))
    except Exception as e:
        print("[warn] session write:", e)

# ---- auto MFA: read the PingID one-time code from the internode inbox --------
IMAP_HOST = "imap.themessagingco.com.au"
IMAP_USER = "seangriffin@internode.on.net"
IMAP_PASS = os.path.expanduser("~/teamsreader/teams_internode_imap.dat")

def _html_body(msg):
    html = text = None
    for part in msg.walk():
        ct = part.get_content_type()
        try: payload = part.get_payload(decode=True)
        except Exception: payload = None
        if payload is None: continue
        s = payload.decode(part.get_content_charset() or "utf-8", "replace")
        if ct == "text/html" and html is None: html = s
        elif ct == "text/plain" and text is None: text = s
    return html or text or ""

def _extract_code(body):
    # the big centred <p style="font-size:26px..."> holds the OTP
    m = re.search(r'font-size:\s*26px[^>]*>\s*(\d{6})', body)
    if m: return m.group(1)
    m = re.search(r'>\s*(\d{6})\s*<', body)              # a 6-digit number alone in a tag
    if m: return m.group(1)
    m = re.search(r'passcode[^0-9]{0,40}(\d{6})', body, re.I)
    return m.group(1) if m else None

def _pingid_msgs(M):
    """[(epoch, code)] for PingID 'Authentication Request' emails in the inbox."""
    out = []
    typ, data = M.search(None, '(FROM "pingidentity.com")')
    for i in reversed((data[0].split())[-15:]):
        typ, md = M.fetch(i, "(BODY.PEEK[])")
        if not md or not md[0]: continue
        msg = email.message_from_bytes(md[0][1])
        if "authentication request" not in str(msg.get("Subject", "")).lower(): continue
        try: ts = email.utils.parsedate_to_datetime(msg.get("Date")).timestamp()
        except Exception: ts = 0
        out.append((ts, _extract_code(_html_body(msg))))
    return out

def latest_pingid_ts():
    """Newest existing PingID email time (baseline), so we only accept a NEWER one."""
    try:
        pw = open(IMAP_PASS).read().strip()
        M = imaplib.IMAP4_SSL(IMAP_HOST, 993); M.login(IMAP_USER, pw); M.select("INBOX")
        msgs = _pingid_msgs(M); M.logout()
        return max([t for t, _ in msgs], default=0)
    except Exception as e:
        print("[mfa] baseline read failed:", e); return 0

def read_mfa_code(baseline_ts, timeout=150):
    """Poll the inbox for a PingID code email newer than baseline_ts. Code or None."""
    try: pw = open(IMAP_PASS).read().strip()
    except Exception as e: print("[mfa] no imap password:", e); return None
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            M = imaplib.IMAP4_SSL(IMAP_HOST, 993); M.login(IMAP_USER, pw); M.select("INBOX")
            newer = [(t, c) for t, c in _pingid_msgs(M) if c and t > baseline_ts]
            M.logout()
            if newer:
                newer.sort()
                return newer[-1][1]
        except Exception as e:
            print("[mfa] poll error:", e)
        time.sleep(6)
    return None

# ---- 0) is the saved session still valid? ----------------------------------
print("[0] trying saved session ...")
code, body = get_user()
print("    /api/user:", code)
if code == 200:
    write_session()
    show_events(body)
    print("\n*** session still valid - no login needed ***"); sys.exit(0)

dm = WebKit2.WebsiteDataManager(base_data_directory=PROFILE,
                                base_cache_directory=os.path.join(PROFILE, "cache"))
dm.get_cookie_manager().set_accept_policy(WebKit2.CookieAcceptPolicy.ALWAYS)
ctx = WebKit2.WebContext.new_with_website_data_manager(dm)
view = WebKit2.WebView.new_with_context(ctx)
win = Gtk.OffscreenWindow(); win.add(view); win.show_all()

def wait_for(expr, timeout=90):
    box = {"v": None}; loop = GLib.MainLoop()
    def on_res(wv, r, _):
        try: val = wv.run_javascript_finish(r).get_js_value().to_string()
        except Exception: val = ""
        if val: box["v"] = val; loop.quit()
    def poll():
        view.run_javascript("(%s||'')" % expr, None, on_res, None); return True
    tid = GLib.timeout_add(1000, poll)
    GLib.timeout_add_seconds(timeout, lambda: (loop.quit(), False)[1])
    loop.run(); GLib.source_remove(tid)
    return box["v"]

print("[*] loading login page; waiting for reCAPTCHA to initialise (can take ~1-2 min on the Pi) ...")
view.load_uri(LOGIN_URL)
ready = wait_for("(typeof grecaptcha!=='undefined' && grecaptcha.execute ? '1':'')", 170)
if ready != "1":
    print("   reCAPTCHA never became available (grecaptcha undefined). Aborting."); sys.exit(2)
print("   reCAPTCHA ready.")

def mint():
    view.run_javascript(
        "window.__t='';try{grecaptcha.ready(function(){grecaptcha.execute('%s',{action:'login'})"
        ".then(function(t){window.__t=t;}).catch(function(e){window.__t='ERR:'+e;});});}"
        "catch(e){window.__t='ERR:'+e;}" % KEY,
        None, None, None)
    t = wait_for("window.__t", 60)
    if not t or t.startswith("ERR:"):
        raise RuntimeError("token mint failed: %s" % t)
    return t

def post_login(extra):
    tok = mint()
    body = json.dumps(dict({"email": EMAIL, "password": PW, "captchaToken": tok}, **extra)).encode()
    req = urllib.request.Request(BASE + "/api/authentication/login", data=body,
                                 headers=CLIENT, method="POST")
    try:
        r = opener.open(req, timeout=25); return r.getcode(), r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")

# ---- 1) login ---------------------------------------------------------------
print("[1] priming CSRF + login ...")
try:
    opener.open(urllib.request.Request(BASE + "/api/token/csrf-token",
                headers={k: v for k, v in CLIENT.items() if k != "content-type"}), timeout=25).read()
except Exception as e:
    print("    csrf warn:", e)

code, body = post_login({})
print("    login:", code)
logged_in = code in (200, 201)

if code == 420:
    j = {}
    try: j = json.loads(body)
    except Exception: pass
    mfasid = j.get("mfaSessionId")
    print("[2] MFA required - requesting email code (mfaMedium=2) ...")
    baseline = latest_pingid_ts()                 # ignore any older PingID codes
    code2, body2 = post_login({"mfaMedium": 2, "isNewDevice": False, "mfaSessionId": mfasid})
    print("    send-code:", code2)
    try: mfasid = json.loads(body2).get("mfaSessionId", mfasid)
    except Exception: pass
    print("    polling inbox for the PingID code (up to 150s) ...")
    pin = read_mfa_code(baseline, timeout=150)
    if pin:
        print("    got code from email.")
    elif NONINT:
        print("    no code email arrived in time; skipping (will retry next run)."); sys.exit(3)
    else:
        pin = input("    Enter the 6-digit QParents code from your email: ").strip()
    code3, body3 = post_login({"mfaMedium": 2, "isNewDevice": False,
                               "mfaSessionId": mfasid, "token": pin})
    print("    verify:", code3)
    if code3 not in (200, 201):
        print("    verify body:", body3[:200])
    logged_in = code3 in (200, 201)
elif code not in (200, 201):
    print("    login failed:", body[:200]); sys.exit(0)

if not logged_in:
    print("    not logged in; stopping."); sys.exit(0)

# ---- 2) save cookies + read events -----------------------------------------
try:
    jar.save(ignore_discard=True, ignore_expires=True); os.chmod(COOKIES, 0o600)
    names = sorted({c.name for c in jar})
    print("[*] saved cookies:", names)
except Exception as e:
    print("[warn] cookie save:", e)
write_session()

print("[3] GET /api/user ...")
code, body = get_user()
print("    /api/user:", code)
if code == 200:
    show_events(body)
    print("\n*** PI-ONLY LOGIN WORKS END-TO-END ***")
