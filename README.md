# Maestro-Editor 4.7.7-B2 (Beta 2)

This fork written and maintained by Sinduriel of The Feral Housewives (Sting)

For End Users:

See [latest releases](https://github.com/avalkon/maestro-editor/releases)  for downloads.

This beta combines Maestro 4.7.6-derived source with the integrated piano-roll
updates and introduces **File → New ABC Composition**, with project-owned notes,
LOTRO instrument parts, and preview-visible authored parts. Import-based export
still omits empty tracks, as in upstream Maestro.

If you encounter any issues you can't figure out, please feel free to submit an 
issue or contact me in-game, or find me through the official LotRO or Sting discords.


## Known beta caveats

- Original MIDI edits can be staged as snapshots but do not necessarily replace
  Maestro's underlying conversion source.
- ABC part delta rebasing after major arrangement changes can be imperfect.
- Strict LOTRO exported ABC should be validated in ABC Player; keep backups.
- Source history documents may reference earlier editor versions; those are
  historical descriptions, not claims about the current 4.7.7-B2 version.

See the editable 4.7.7-B1 User Manual (most sections remain applicable); see docs/NEW_ABC_COMPOSITION_B2.md for workflows, shortcuts, and limitations.

## Upstream project history

<!-- Project -->

<p align="center">
  <a href="https://discord.gg/f7hGqmY9xF"><img src="https://img.shields.io/discord/1496644400590094540?logo=discord&logoColor=white&label=Discord&color=5865F2" alt="Discord" /></a>
  <a href="https://maestro.miraheze.org"><img src="https://img.shields.io/badge/Wiki-maestro.miraheze.org-yellow?logo=googlechrome&logoColor=white" alt="Wiki" /></a>
  <a href="https://github.com/NikolaiVChr/maestro/releases"><img src="https://img.shields.io/github/v/release/NikolaiVChr/maestro?label=Latest%20Release" alt="Latest release" /></a>
  <a href="https://github.com/NikolaiVChr/maestro/releases"><img src="https://img.shields.io/github/downloads/NikolaiVChr/maestro/total?label=Downloads" alt="Downloads" /></a>
</p>

<!-- Development -->

<p align="center">
  <img src="https://img.shields.io/badge/Windows-supported-0078D6?logo=windows&logoColor=white" alt="Windows" />
  <img src="https://img.shields.io/badge/Linux-supported-FCC624?logo=linux&logoColor=white" alt="Linux" />
  <img src="https://img.shields.io/badge/macOS-supported-000000?logo=apple&logoColor=white" alt="macOS" />
</p>


# Maestro and Abc Player

**Maestro**, **Abc Player** and **Abc Tools** are music apps for [The Lord of the Rings Online](https://www.lotro.com/).

See the [latest releases](https://github.com/NikolaiVChr/maestro/releases) for downloads.

Project documentation is available on the **[Maestro Wiki](https://maestro.miraheze.org/)**.

For questions, discussion, development feedback or contact with other users, join the **[Maestro Discord](https://discord.gg/f7hGqmY9xF)**.

## Developers

Maestro and Abc Player were originally created by **Digero of Landroval**.

They are currently maintained and further developed by **Aifel of Meriadoc** and **Elamond of Peregrin**.

**Asgloin of Meriadoc** and **Karloman** have also contributed to the codebase.

## Data Sources

Lotro instrument names and samples are from Lord of the Rings Online.  

XG, GS and GM2 instrument and drum kit names, excluding individual drum hit names, are largely based on data from **[Jazz-soft](https://github.com/jazz-soft/JZZ-midi-GM)**.
