#!/bin/bash
set -e
cd "$(dirname "$0")/.."
./gradlew build -x test
java --enable-preview -cp build/libs/arb-bot-1.0.0.jar com.arbbot.Main
