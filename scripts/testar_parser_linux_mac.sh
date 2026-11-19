#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
kotlinc "$ROOT/app/src/main/java/br/com/fitlauncher/px5/Elm327Protocol.kt" \
  "$ROOT/tests/Elm327ProtocolTests.kt" -include-runtime -d "$TMP/elm-tests.jar"
java -jar "$TMP/elm-tests.jar"
