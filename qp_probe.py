#!/usr/bin/env python3
# Inspect QParents consent time fields. Needs a fresh session (run qp_login.py first).
#   python3 qp_probe.py                -> list all consents (title + eqId + id)
#   python3 qp_probe.py <text>         -> dump the consent(s) whose title matches <text>
#   python3 qp_probe.py <eqId> <id> <schoolCode>  -> dump that exact consent
import os, sys, json, re, urllib.request, urllib.error

BASE    = "https://qparents.qld.edu.au"
SESSION = os.path.expanduser("~/teamsreader/qparents_session.dat")
H = {"accept": "application/json", "Cookie": open(SESSION).read().strip(),
     "Client-Platform": "web", "Client-App-Version": "Web", "User-Agent": "Mozilla/5.0"}

def get(path):
    try:
        return urllib.request.urlopen(urllib.request.Request(BASE + path, headers=H), timeout=25)\
               .read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        print("HTTP", e.code, "on", path, "- run qp_login.py to refresh the session"); sys.exit(1)

def find(o, k):
    if isinstance(o, dict):
        if k in o: return o
        for v in o.values():
            r = find(v, k)
            if r is not None: return r
    elif isinstance(o, list):
        for v in o:
            r = find(v, k)
            if r is not None: return r
    return None

def dump(eqId, cid, sch, full=False):
    body = get("/api/consent/%s/%s/%s" % (eqId, cid, sch))
    root = json.loads(body)
    data = find(root, "activityDate") or {}
    print("\n== %s   (ref %s)" % (root.get("title"), root.get("reference")))
    if full:
        print("   ALL submission-data fields (key = value):")
        for k, v in data.items():
            vs = v if isinstance(v, str) else json.dumps(v)
            if len(vs) > 500: vs = vs[:500] + " ...[truncated]"
            print("      %-28s = %s" % (k, vs))
    else:
        print("   date/time-ish fields in submission data:")
        for k, v in data.items():
            if any(x in k.lower() for x in
                   ("date", "time", "start", "end", "depart", "return", "duration",
                    "part", "when", "session", "arrive", "am", "pm")):
                print("      %-24s = %r" % (k, v))
    print("   time-looking values anywhere:",
          sorted(set(re.findall(r'\d{1,2}:\d{2}\s*[ap]m', body, re.I))))

args = sys.argv[1:]
FULL = any(a.lower() == "full" for a in args)
args = [a for a in args if a.lower() != "full"]
if len(args) == 3:
    dump(args[0], args[1], args[2], FULL); sys.exit(0)

u = json.loads(get("/api/user?IncludeFutureStudents=true"))
studs = []
for s in u.get("students", []):
    eq = s.get("oneSchoolId"); enr = s.get("enrolments") or []
    sch = enr[0].get("schoolCode") if enr else None
    if eq and sch: studs.append((eq, sch))
q = "/api/consent/summary" + "".join(
    ("?" if i == 0 else "&") + "Students=%s.%s" % (eq, sch) for i, (eq, sch) in enumerate(studs))
reqs = json.loads(get(q)).get("consentRequests", [])

search = args[0].lower() if args else ""
matched = 0
for r in reqs:
    title = r.get("title", "")
    if search and search not in title.lower():
        continue
    if not search:
        print("%-55s eqId=%s id=%s" % (title[:55], r.get("eqId"), r.get("id")))
    else:
        dump(r.get("eqId"), r.get("id"), r.get("schoolCode"), FULL); matched += 1
if search and matched == 0:
    print("no consent matched:", search)
