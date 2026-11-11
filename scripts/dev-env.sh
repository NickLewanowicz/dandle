#!/bin/zsh
# Dev toolchain for babybuddy-next builds on this machine (Nick's Mac).
# Usage: source scripts/dev-env.sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$HOME/tools/gradle-8.10.2/bin:$PATH"
# Server for local testing
export BABYBUDDY_URL="http://localhost:8000"
export BABYBUDDY_EMULATOR_URL="http://10.0.2.2:8000"
# Release signing password (security rotation 2026-11-10): lives OUTSIDE the repo in
# ~/.config/dandle/release-keystore.env (chmod 600, same pattern as pb-admin.env).
# app/app/build.gradle.kts requires DANDLE_STORE_PASSWORD for assembleRelease and fails
# loudly without it — no fallback password exists anymore.
if [[ -f "$HOME/.config/dandle/release-keystore.env" ]]; then
  source "$HOME/.config/dandle/release-keystore.env"
  # Sourcing alone sets a shell variable; `export VAR` (no value) marks it for child
  # processes — Gradle reads it from the ENVIRONMENT, not the shell.
  export DANDLE_STORE_PASSWORD
fi
