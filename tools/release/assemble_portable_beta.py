#!/usr/bin/env python3
"""Assemble the 4.7.7-B1 portable release from a previously extracted 4.7.6 ZIP
and a successful 4.7.7-B1 Maven build. Use alongside the launcher assets folder.
"""
from pathlib import Path
import argparse,zipfile,sys
ap=argparse.ArgumentParser()
ap.add_argument('--original',required=True,help='original Maestro_v4.7.6-release.zip')
ap.add_argument('--target',required=True,help='the *new* maestro2/target directory')
ap.add_argument('--assets',required=True,help='directory containing updated EXEs/config')
ap.add_argument('--output',required=True,help='output ZIP filename')
a=ap.parse_args()
target=Path(a.target);assets=Path(a.assets);output=Path(a.output)
names=['Maestro.jar','AbcPlayer.jar','AbcTools.jar']
missing=[n for n in names if not (target/n).is_file()]
if missing:sys.exit('Missing freshly compiled JARs: '+', '.join(missing))
# Verify every JAR is from a beta build; old 4.7.6 JARs must not be relabeled.
for jar in names:
  try:
    with zipfile.ZipFile(target/jar) as j:
      for resource, expected in [
          ('com/digero/maestro/version.properties','version.Maestro=4.7.7-B1'),
          ('com/digero/abcplayer/version.properties','version.AbcPlayer=4.7.7-B1')]:
        if expected not in j.read(resource).decode('utf-8'):
          sys.exit(jar + ' does not contain ' + expected + '; rebuild all JARs')
  except (zipfile.BadZipFile, KeyError) as error:
    sys.exit('Cannot verify ' + jar + ': ' + str(error))
if not all((assets/x).is_file() for x in ['Maestro.exe','AbcPlayer.exe','AbcTools.exe']):sys.exit('Launcher asset folder incomplete')
with zipfile.ZipFile(a.original) as inz,zipfile.ZipFile(output,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=7) as out:
  members=inz.namelist();prefix=next((x[:x.index('/app/')] for x in members if x.endswith('/app/Maestro.jar')),None)
  if prefix is None:sys.exit('Unrecognized original release layout')
  dest='Maestro-Editor_4.7.7-B1/'
  for m in inz.infolist():
   if m.is_dir():continue
   rel=m.filename[len(prefix)+1:] if m.filename.startswith(prefix+'/') else None
   if not rel:continue
   if rel in [f'app/{n}' for n in names]: continue
   replacement=assets/rel
   # Use updated launcher files and ONLY replace original launcher scripts by name when available.
   if replacement.is_file(): data=replacement.read_bytes()
   else:data=inz.read(m.filename)
   out.writestr(dest+rel,data)
  for n in names:out.write(target/n,dest+'app/'+n)
  out.writestr(dest+'README-4.7.7-B1.txt',(assets/'README-4.7.7-B1.txt').read_bytes())
with zipfile.ZipFile(output) as z:
 broken=z.testzip()
 if broken:sys.exit('Archive checksum failed: '+broken)
print('Wrote',output,'size',output.stat().st_size)
