# Maestro-Editor 4.7.7-B1 — Beta 1

This beta combines Maestro 4.7.6-derived source with the integrated piano-roll
updates through editor v1.5.6 (instrument pitch and LOTRO timing fixes).

User-visible versions: **4.7.7-B1** in app titles/About/export metadata,
Windows PE FileVersion/ProductVersion strings, and launcher configuration.
The PE fixed numeric version is **4.7.7.1** because those numeric fields
cannot contain `-B1`.

## Build

From `maestro2/` using JDK 21 + Maven: `mvn clean test package`.
This produces `target/Maestro.jar`, `target/AbcPlayer.jar`, and `target/AbcTools.jar`.
Use the separate launcher package to assemble a portable release from matching JARs.

This environment tested the new version parser in Java 21 and validated PE
resource structure. It did NOT complete a full Maven release build, test
Windows executable launch, or verify audio on physical devices.

## Known beta caveats

- Original MIDI edits can be staged as snapshots but do not necessarily replace
  Maestro's underlying conversion source.
- ABC part delta rebasing after major arrangement changes can be imperfect.
- Strict LOTRO exported ABC should be validated in ABC Player; keep backups.
- Source history documents may reference earlier editor versions; those are
  historical descriptions, not claims about the current 4.7.7-B1 version.

See the editable 4.7.7-B1 User Manual for workflows, shortcuts, and limitations.
