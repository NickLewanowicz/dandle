#!/usr/bin/env bash
# Clear django-axes login lockouts on the local dev server (docker container
# `babybuddy`, sqlite DB). The capture suite performs ONE deliberate
# wrong-password login per run (for the error-banner capture), and django-axes
# locks `admin` out after 5 failures within its cooloff (1 h) — so a few
# capture runs in a session lock the login view with HTTP 429. Run this to
# reset; safe: it only deletes axes' own bookkeeping rows.
#
# usage: axes-reset.sh
set -euo pipefail

docker exec babybuddy python -c "
import sqlite3
con = sqlite3.connect('/config/data/db.sqlite3')
c = con.cursor()
for t in ('axes_accessattempt', 'axes_accessfailurelog', 'axes_accesslog'):
    c.execute(f'DELETE FROM {t}')
con.commit()
con.close()
print('axes lockouts cleared')
"
