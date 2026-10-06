#!/bin/bash
# Compares the Swing GUI with demo/prototype.html in every view, light and dark, at 1280x800 and 1920x1080 (see README.md here).
# Needs: ./mvnw package; npm install && npx playwright install chromium (in this directory).
set -e
cd "$(dirname "$0")"
ROOT=../..
CP=$ROOT/gui/target/test-classes:$ROOT/gui/target/jvmeter-gui.jar
OUT=$ROOT/gui/target/compare
SIZES=${SIZES:-1280x800 1920x1080}
# the browser and the GUI render at the same time, then one JVM compares all pairs in parallel
node ref.js "$ROOT/demo/prototype.html" "$OUT" $SIZES &
java -Djava.awt.headless=true -cp "$CP" jvmeter.gui.Shot "$OUT" "${SIZES// /,}" 2>/dev/null
wait $!
java -cp "$CP" jvmeter.gui.Compare $(for s in $SIZES; do echo "$OUT/$s"; done)
