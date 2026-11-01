#!/usr/bin/env python3
"""Apply the Dandle Cloud schema to a PocketBase instance (brief 05 §5.1).

Reads superuser credentials from PB_ADMIN_EMAIL / PB_ADMIN_PASSWORD (or
~/.config/dandle/pb-admin.env). Idempotent: adds missing users fields, flips
identity to username, and creates sync_records with zero-knowledge rules.
"""
import json
import os
import sys
import urllib.request
from pathlib import Path

BASE = os.environ.get("DANDLE_PB_URL", "https://dandle.pockethost.io").rstrip("/")

ENV_FILE = Path.home() / ".config/dandle/pb-admin.env"

# QA 2026-10-31: dots + hyphens in cloud usernames (the strict [a-zA-Z0-9_] pattern
# dead-ended dotted names — see app-side CloudBackend.signUpInternal mapping fix).
USERNAME_PATTERN = "^[a-zA-Z0-9._-]{3,30}$"


def load_env():
    for key in ("PB_ADMIN_EMAIL", "PB_ADMIN_PASSWORD"):
        if not os.environ.get(key) and ENV_FILE.exists():
            for line in ENV_FILE.read_text().splitlines():
                if line.startswith(f"{key}="):
                    os.environ[key] = line.split("=", 1)[1]
    email, password = os.environ.get("PB_ADMIN_EMAIL"), os.environ.get("PB_ADMIN_PASSWORD")
    if not email or not password:
        sys.exit(f"missing PB_ADMIN_EMAIL/PB_ADMIN_PASSWORD (create {ENV_FILE}, chmod 600)")
    return email, password


def api(method, path, token=None, body=None):
    req = urllib.request.Request(BASE + path, method=method)
    # pockethost's edge rejects default python-urllib UAs (403) — identify ourselves.
    req.add_header("User-Agent", "dandle-apply-schema/1.0")
    if token:
        req.add_header("Authorization", token)
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, data) as resp:
            return json.loads(resp.read())
    except urllib.error.HTTPError as e:
        body = e.read().decode()
        raise SystemExit(f"{method} {path} -> HTTP {e.code}: {body[:500]}")


def main():
    email, password = load_env()
    auth = api("POST", "/api/collections/_superusers/auth-with-password",
               body={"identity": email, "password": password})
    token = auth["token"]
    print("authenticated as superuser")

    # -- users: identity by username + envelope fields -------------------------
    users = api("GET", "/api/collections/users", token)
    if users is None:
        sys.exit("users collection missing")
    patch = {k: users[k] for k in (
        "name", "type", "system", "schema", "fields", "indexes", "listRule", "viewRule",
        "createRule", "updateRule", "deleteRule", "mfa", "passwordAuth", "oauth",
    ) if k in users}
    # PB >= 0.30: identity lives under passwordAuth.identityFields (top-level
    # identityFields is the pre-0.30 key and is silently ignored here). The username
    # field must exist BEFORE identity can point at it.
    patch["passwordAuth"] = dict(patch.get("passwordAuth") or {})
    patch["passwordAuth"]["enabled"] = True
    patch["passwordAuth"]["identityFields"] = ["username"]
    existing = {f["name"] for f in patch.get("fields", [])}

    def field(name, ftype, **kw):
        f = {"type": ftype, "name": name, "presentable": False}
        f.update(kw)
        return f

    new_fields = [
        # PB 0.30's users template ships email-only identity — add the username field.
        # Dots + hyphens allowed (QA 2026-10-31: the strict [a-zA-Z0-9_] pattern silently
        # dead-ended dotted names — the app mapped the pattern-400 to "username taken").
        field("username", "text", required=True, min=3, max=30,
              pattern=USERNAME_PATTERN, autogeneratePattern=""),
        field("enc_salt", "text", min=16),
        field("enc_params", "text"),
        field("wrapped_dek", "text", min=32),
        field("crypto_version", "number", onlyInt=True, min=1),
        # Brief 06 §2: optional, unverified contact address for service-change warnings —
        # NOT PB's auth email (no reset promises, no identity coupling). Cleartext by design
        # (it is contact metadata); the app omits the field entirely when the user leaves it
        # blank. No pattern beyond a max length.
        field("contact_email", "text", max=254),
    ]
    for f in new_fields:
        if f["name"] not in existing:
            patch["fields"].append(f)
            print(f"users: + {f['name']}")
    # Idempotent re-apply: on instances where the username field ALREADY exists (pockethost
    # shipped the old [a-zA-Z0-9_] pattern), only-adding would leave the strict pattern in
    # place — sync the pattern in place, touching no other attribute.
    for f in patch["fields"]:
        if f.get("name") == "username" and f.get("pattern") != USERNAME_PATTERN:
            f["pattern"] = USERNAME_PATTERN
            print(f"users: username pattern -> {USERNAME_PATTERN}")
    # Brief 05 owner decision D3: username + password only — email optional (no reset flow;
    # the passphrase is the real key). PB's default users schema ships email as required.
    for f in patch["fields"]:
        if f.get("name") == "email" and f.get("required"):
            f["required"] = False
            print("users: email -> optional")
    if "username" in {f["name"] for f in patch["fields"]}:
        idx = "CREATE UNIQUE INDEX idx_users_username ON users (username) WHERE username != ''"
        if idx not in patch.get("indexes", []):
            patch.setdefault("indexes", []).append(idx)
    api("PATCH", f"/api/collections/{users['id']}", token, patch)
    print("users: identity=username, envelope fields ensured")

    # -- sync_records: the encrypted event/child store -------------------------
    try:
        api("GET", "/api/collections/sync_records", token)
        print("sync_records: already exists")
        return
    except SystemExit as e:
        if "404" not in str(e):
            raise
        print("sync_records: not found — creating")

    owner_rule = "@request.auth.id != '' && user = @request.auth.id"
    sync_records = {
        "name": "sync_records",
        "type": "base",
        "system": False,
        "fields": [
            {"type": "relation", "name": "user", "required": True, "presentable": False,
             "collectionId": users["id"], "cascadeDelete": True, "minSelect": 1, "maxSelect": 1},
            {"type": "text", "name": "type", "required": True, "presentable": False},
            {"type": "text", "name": "payload", "required": True, "presentable": False},
            {"type": "number", "name": "client_updated", "onlyInt": True, "presentable": False},
            {"type": "bool", "name": "deleted", "presentable": False},
            {"type": "text", "name": "device", "presentable": False},
            {"type": "number", "name": "schema_v", "onlyInt": True, "presentable": False},
            {"type": "autodate", "name": "created", "onCreate": True, "onUpdate": False, "presentable": False},
            {"type": "autodate", "name": "updated", "onCreate": True, "onUpdate": True, "presentable": False},
        ],
        "listRule": owner_rule,
        "viewRule": owner_rule,
        "createRule": owner_rule,
        "updateRule": owner_rule,
        # Tombstones only (brief 05 §5.1): records are never hard-deleted by users.
        "deleteRule": None,
        "indexes": [
            "CREATE UNIQUE INDEX idx_sync_user_type_id ON sync_records (user, type, id)",
            "CREATE INDEX idx_sync_user_updated ON sync_records (user, updated)",
        ],
    }
    api("POST", "/api/collections", token, sync_records)
    print("sync_records: created (zero-knowledge rules, tombstone-only)")


if __name__ == "__main__":
    main()
