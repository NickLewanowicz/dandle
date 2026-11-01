package app.dandle.data.backend

/**
 * Which backend this install talks to (brief 05 §3). One install = one backend; switching is
 * sign-out with teeth (drained queue required, local data wiped). Default BABYBUDDY keeps
 * every existing session valid across the upgrade.
 */
enum class Backend {
    /** Self-hosted Baby Buddy server (the original, pinned behavior). */
    BABYBUDDY,

    /** This device only: no server, no account (brief 05 §4). */
    LOCAL,

    /** Dandle Cloud: end-to-end-encrypted PocketBase sync (brief 05 §5). */
    CLOUD,
}
