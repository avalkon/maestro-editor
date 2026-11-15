#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
bash tools/note-editor/build-editor-only.sh
mkdir -p target/note-editor-tests
javac --release 21 -encoding UTF-8 -cp target/note-editor-classes \
  -d target/note-editor-tests tools/note-editor/EditorSmokeTest.java tools/note-editor/OrganicSmoke.java tools/note-editor/InteractionSmokeTest.java tools/note-editor/LiveOverlaySmokeTest.java tools/note-editor/TransportBridgeSmokeTest.java tools/note-editor/SpaceKeySmokeTest.java tools/note-editor/SpaceKeyWindowSmokeTest.java tools/note-editor/InstrumentRangeSmokeTest.java tools/note-editor/LotroTimingSmokeTest.java tools/note-editor/CopyPasteSmokeTest.java
java -ea -cp target/note-editor-classes:target/note-editor-tests \
  com.digero.maestro.noteedit.EditorSmokeTest
java -Djava.awt.headless=true -ea -cp target/note-editor-classes:target/note-editor-tests \
  com.digero.maestro.noteedit.CopyPasteSmokeTest
java -Djava.awt.headless=true -ea -cp target/note-editor-classes:target/note-editor-tests \
  com.digero.maestro.noteedit.InteractionSmokeTest
java -Djava.awt.headless=true -ea -cp target/note-editor-classes:target/note-editor-tests \
  com.digero.maestro.noteedit.TransportBridgeSmokeTest
java -Djava.awt.headless=true -ea -cp target/note-editor-classes:target/note-editor-tests \
  com.digero.maestro.noteedit.SpaceKeySmokeTest
java -Djava.awt.headless=true -ea -cp target/note-editor-classes:target/note-editor-tests \
  com.digero.maestro.noteedit.LiveOverlaySmokeTest "$@"
java -Djava.awt.headless=true -ea -cp target/note-editor-classes:target/note-editor-tests \
  com.digero.maestro.noteedit.InstrumentRangeSmokeTest
java -Djava.awt.headless=true -ea -cp target/note-editor-classes:target/note-editor-tests \
  com.digero.maestro.noteedit.LotroTimingSmokeTest
if [[ $# -gt 0 ]]; then
  java -ea -cp target/note-editor-classes:target/note-editor-tests \
    com.digero.maestro.noteedit.OrganicSmoke "$1"
fi
