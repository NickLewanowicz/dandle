# Your data & security

Baby Buddy Next talks to exactly one place: **your own Baby Buddy server**. There is no
cloud run by us and no account with us — we never see your family's data, so we can't
lose it, sell it, or leak it.

**The short version**

- Your entries live on your phone and on **your** server. Nowhere else.
- The app contains **no analytics, no ads, no crash reporting** — it never contacts anyone but your server.
- An invalid or self-signed https certificate is **refused, never bypassed**: the connection simply fails.
- Plain `http://` servers are allowed (common on a home LAN) but only after a clear warning at login.
- Your login key is stored app-private, and the app is **excluded from Android backups**.

## Where your data is and where it goes

| | |
|---|---|
| **Stored on the device** | A local copy of your entries (feeds, diapers, sleep, notes, pumping, tummy time), running timers, your server address, username, and API key — all in the app's private storage, unreadable by other apps. |
| **Leaves the phone** | Only what you log (creates, edits, deletes), reads of the timeline, and your credentials at sign-in. Every request goes **to the server address you entered and nothing else**. No usage stats, no crash reports, no location. |
| **When** | At sign-in; when you open the app or pull to refresh; shortly after each entry you write (queued automatically while offline — see [No signal? Keep logging](offline.md)). |
| **How** | https with your server's certificate verified against Android's system certificate store. If the certificate is invalid or self-signed, the connection **fails** and you see "Couldn't reach the server" — the app never lowers its guard to get through. |
| **What the server sees** | Ordinary web requests from your phone's IP, the same as from the Baby Buddy web UI or the original Android app. |

## The details

**Your key and backups.** The API key from login sits in the app's private preferences
file (`session.preferences_pb`). The app manifest sets `allowBackup="false"`, so Android
cloud backups and phone-to-phone setup transfers never copy the key or your data.
Signing out erases the key and the local cache; your server keeps everything (spec
§1.1–1.2).

**Certificates are never bypassed.** The app ships no custom certificate handling:
networking uses OkHttp's defaults with trust anchors limited to the Android system CA
store (`network_security_config.xml`). A server with an invalid, expired, or self-signed
certificate cannot be connected to — the attempt fails as an ordinary unreachable-server
error (spec §10: no crash, no insecure fallback). To use self-signed https, install your
CA on the device; the app itself will never skip validation.

**Plain http is a warned choice.** If the address you type starts with `http://`, the
login screen asks before any connection: *"This server uses http:// — data is not
encrypted on the way. Continue?"* Cancel stops everything (spec §1.1).

**What the app talks to.** Every request is rewritten to the server URL you entered —
Baby Buddy's own `/api/` endpoints plus its login page, nothing else. The app requests
only the Internet and network-state permissions, and its libraries (UI: Compose;
database: Room; networking: OkHttp/Retrofit; preferences: DataStore; background sync:
WorkManager; widgets: Glance) include no analytics or tracking components. Debug builds
used for testing log network traffic to the device log with your key always masked;
release builds don't log.

**Deleting an entry.** Deleting in the app removes it from the phone; if it had already
synced, the deletion is queued for your server too (if another device deleted it first,
that counts as done). An entry that never made it online is deleted locally only —
the server never knew it (spec §4.2, §6).
