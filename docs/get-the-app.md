---
title: Get the app
---

# Get the app

**Status: beta.** The app builds, installs, and runs against a real Baby Buddy server — but it is **not on Google Play or F-Droid yet**, and there is no release date to promise. Here's the honest picture of how to get it today, or how to be ready when it ships.

## What you need

- **An Android phone running Android 10 or newer.** (Check under Settings → About phone → Android version.)
- **Your own Baby Buddy server.** Baby Buddy is the free, open-source tracker this app talks to. It runs on a computer you control — a Raspberry Pi in a closet counts. If that sounds like someone else's hobby, it can be: you or a geeky friend can set one up in an evening. See [the Baby Buddy project](https://github.com/babybuddy/babybuddy) for what it does.
- Nothing else. No cloud account, no sign-up. On first launch the app asks for your server's address and your Baby Buddy login, and after that everything stays between your phone and your server. (If your server runs on plain `http://` — common at home — the app asks once to confirm; that's expected.)

## Path 1: build it from source

You — or the geeky friend — need [Android Studio](https://developer.android.com/studio) and about ten minutes:

1. Install Android Studio (it provides the Java and Android tools the build needs).
2. Download this project — the green **Code** button on the repository page, or `git clone` if you prefer.
3. From the project folder, build it:

``` bash
source scripts/dev-env.sh && cd app && ./gradlew :app:assembleDebug
```

The first command just picks the right Java version — Gradle is picky about that. The finished app is written to `app/build/outputs/apk/debug/app-debug.apk`. Copy it to your phone and tap it to install; Android asks you to allow installs from this source, which is normal for anything outside the stores. If you'd rather not touch a terminal, open the `app/` folder in Android Studio and press **Run** with your phone plugged in.

## Path 2: wait for a release

Keep an eye on this project's repository for a **Releases** entry. When the first one lands, installing will be a normal download-and-tap. It ships when it's ready — no date promised.
