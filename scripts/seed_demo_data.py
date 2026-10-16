#!/usr/bin/env python3
"""Seed the local Baby Buddy dev server with realistic sample data.

Usage: python3 scripts/seed_demo_data.py [base_url] [token]
Defaults: base_url=http://localhost:8000, token read from /tmp/bb_token
"""
import json
import sys
import urllib.request
from datetime import datetime, timedelta

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8000"
TOKEN = sys.argv[2] if len(sys.argv) > 2 else open("/tmp/bb_token").read().strip()
TZ = "-07:00"  # America/Los_Angeles, matches the dev container

def call(method, path, payload=None):
    req = urllib.request.Request(
        BASE + path,
        data=json.dumps(payload).encode() if payload is not None else None,
        method=method,
        headers={
            "Content-Type": "application/json",
            "Authorization": "Token " + TOKEN,
        },
    )
    try:
        with urllib.request.urlopen(req) as r:
            body = r.read().decode()
            return r.status, json.loads(body) if body else None
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:500]

def iso(dt):
    return dt.strftime("%Y-%m-%dT%H:%M:%S") + TZ

now = datetime.now()
def ago(hours):
    return now - timedelta(hours=hours)

# --- Children -------------------------------------------------------------
s, listing = call("GET", "/api/children/")
if listing["count"] == 0:
    s, milo = call("POST", "/api/children/", {
        "first_name": "Milo", "last_name": "Rivera",
        "birth_date": "2026-06-14", "gender": "boy",
    })
    s, zoe = call("POST", "/api/children/", {
        "first_name": "Zoe", "last_name": "Rivera",
        "birth_date": "2025-02-20", "gender": "girl",
    })
else:
    milo, zoe = listing["results"][0], listing["results"][-1]
print("children:", milo["first_name"], zoe["first_name"])
M, Z = milo["id"], zoe["id"]

def feed(child, hours_ago, minutes, method, amount, note=""):
    ftype = "breast milk" if "breast" in method else "formula"
    return call("POST", "/api/feedings/", {
        "child": child,
        "start": iso(ago(hours_ago)), "end": iso(ago(hours_ago - minutes / 60)),
        "type": ftype, "method": method, "amount": amount, "notes": note,
    })

def change(child, hours_ago, wet, solid, color="", amount=""):
    payload = {"child": child, "time": iso(ago(hours_ago)), "wet": wet, "solid": solid}
    if color:
        payload["color"] = color
    if amount:
        payload["amount"] = float(amount)
    return call("POST", "/api/changes/", payload)

def sleep(child, hours_ago, minutes, note=""):
    return call("POST", "/api/sleep/", {
        "child": child,
        "start": iso(ago(hours_ago)), "end": iso(ago(hours_ago - minutes / 60)),
        "notes": note,
    })

def note(child, hours_ago, text):
    return call("POST", "/api/notes/", {
        "child": child, "note": text, "time": iso(ago(hours_ago)),
    })

def pumping(child, hours_ago, minutes, amount):
    return call("POST", "/api/pumping/", {
        "child": child,
        "start": iso(ago(hours_ago)), "end": iso(ago(hours_ago - minutes / 60)),
        "amount": amount, "notes": "",
    })

def tummy(child, hours_ago, minutes, milestone=""):
    return call("POST", "/api/tummy-times/", {
        "child": child,
        "start": iso(ago(hours_ago)), "end": iso(ago(hours_ago - minutes / 60)),
        "milestone": milestone, "notes": "",
    })

results = []
# Milo: a realistic day pattern over the last ~30 hours
pattern = [
    (feed, M, (27, 20, "bottle", 120)), (change, M, (26.5, True, False)),
    (sleep, M, (26, 45)), (feed, M, (24.5, 15, "bottle", 100)),
    (change, M, (23, False, True, "brown")), (tummy, M, (22, 12, "Rolled over!")),
    (feed, M, (21, 25, "both breasts", 0)), (sleep, M, (20, 60)),
    (change, M, (18, True, False)), (feed, M, (17, 20, "bottle", 140)),
    (note, M, (16, "Pediatrician called — next checkup Nov 14.")),
    (sleep, M, (14, 90)), (change, M, (12.5, True, False)),
    (pumping, M, (12, 15, 85)), (feed, M, (11, 18, "bottle", 130)),
    (tummy, M, (10, 8)), (change, M, (8, False, True, "brown")),
    (sleep, M, (7.5, 40)), (feed, M, (6, 22, "left breast", 0)),
    (pumping, M, (5, 12, 70)), (change, M, (4.5, True, False)),
    (feed, M, (3.5, 16, "bottle", 110)), (sleep, M, (2.5, 55)),
    (change, M, (1.5, True, False)), (note, M, (1, "Loved the mobile today!")),
]
for fn, child, *rest in pattern:
    args = [x for item in rest for x in (item if isinstance(item, tuple) else (item,))]
    st, body = fn(child, *args)
    results.append((fn.__name__, st, body if st >= 400 else "ok"))

# Zoe (toddler): sparse older data
for h, m, a in [(50, 10, 240), (26, 12, 260)]:
    st, body = feed(Z, h, m, "bottle", a); results.append(("feed-zoe", st, body if st >= 400 else "ok"))
st, body = change(Z, 25, True, True, "brown"); results.append(("change-zoe", st, body if st >= 400 else "ok"))
st, body = sleep(Z, 24, 70); results.append(("sleep-zoe", st, body if st >= 400 else "ok"))
st, body = note(Z, 5, "Preschool art project sent home."); results.append(("note-zoe", st, body if st >= 400 else "ok"))

fails = [r for r in results if r[1] >= 400]
print(f"created {len(results) - len(fails)} events, {len(fails)} errors")
for name, st, body in fails[:6]:
    print("ERR", name, st, body)
