#!/bin/bash
# NOTE: This script requires a fat-jar. Add the shadow plugin to build.gradle.kts
# before using in production. Use ./gradlew run (with application plugin) instead.
set -e
cd "$(dirname "$0")/.."
./gradlew build -x test
java --enable-preview -cp build/libs/arb-bot-1.0.0.jar com.arbbot.Main
