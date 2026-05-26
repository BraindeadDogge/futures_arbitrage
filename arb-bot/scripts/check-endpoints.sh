#!/bin/bash
set -e
cd "$(dirname "$0")/.."
./gradlew test --tests "*IntegrationTest" -Dtest.tags=integration
