#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
mkdir -p target
rm -rf target/note-editor-classes
mkdir -p target/note-editor-classes
javac --release 21 -encoding UTF-8 -d target/note-editor-classes src/com/digero/maestro/noteedit/*.java
jar --create --file target/MaestroNoteEditor.jar \
  --main-class com.digero.maestro.noteedit.NoteEditorApp -C target/note-editor-classes .
echo "Built target/MaestroNoteEditor.jar (standalone editor only)"
