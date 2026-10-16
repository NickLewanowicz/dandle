#!/bin/zsh
# Dev toolchain for babybuddy-next builds on this machine (Nick's Mac).
# Usage: source scripts/dev-env.sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$HOME/tools/gradle-8.10.2/bin:$PATH"
# Server for local testing
export BABYBUDDY_URL="http://localhost:8000"
export BABYBUDDY_EMULATOR_URL="http://10.0.2.2:8000"
