#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
mkdir -p target/shortcut-classes target/shortcut-tests
javac --release 21 -encoding UTF-8 -d target/shortcut-classes src/com/digero/maestro/shortcuts/*.java
javac --release 21 -encoding UTF-8 -cp target/shortcut-classes -d target/shortcut-tests tools/shortcuts/*Test.java
java -ea -Djava.awt.headless=true -cp target/shortcut-classes:target/shortcut-tests com.digero.maestro.shortcuts.ShortcutSmokeTest
if command -v xvfb-run >/dev/null 2>&1; then
    xvfb-run -a java -ea -cp target/shortcut-classes:target/shortcut-tests com.digero.maestro.shortcuts.ShortcutWindowSmokeTest
else
    echo 'GUI keyboard routing test requires a desktop display or xvfb-run; skipped.'
    echo 'On a desktop, run: java -ea -cp target/shortcut-classes:target/shortcut-tests com.digero.maestro.shortcuts.ShortcutWindowSmokeTest'
fi
