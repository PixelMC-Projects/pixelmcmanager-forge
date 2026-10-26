"""Build allowlisted clean service/supervisor release archives. Never collect a runtime tree."""
from pathlib import Path
import hashlib,json,zipfile
ROOT=Path(__file__).resolve().parents[1]
def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def write(z,name,value):
 if isinstance(value,bytes) and not name.endswith('.jar'):value=value.replace(b'\r\n',b'\n')
 info=zipfile.ZipInfo(name,(2026,10,26,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=(0o100755 if name.endswith('.sh') else 0o100644)<<16;z.writestr(info,value)
def main():
 version=dict(line.split('=',1) for line in (ROOT/'gradle.properties').read_text().splitlines() if '=' in line)['mod_version']
 out=ROOT/'build/distributions';out.mkdir(parents=True,exist_ok=True)
 license_files={name:ROOT/name for name in ['LICENSE','LICENSE-EXCEPTION.md','NOTICE']}
 files={'service.jar':ROOT/f'build/manager-service/pixelmc-manager-service-{version}.jar','README.md':ROOT/'manager-service/README.md','manager.example.json':ROOT/'manager-service/deploy/manager.example.json','Dockerfile':ROOT/'manager-service/deploy/Dockerfile','THIRD_PARTY.md':ROOT/'THIRD_PARTY.md',**license_files}
 service=out/f'pixelmc-manager-service-{version}.zip'
 with zipfile.ZipFile(service,'w',zipfile.ZIP_DEFLATED) as z:
  for name,p in files.items():write(z,name,p.read_bytes())
  write(z,'start.sh','#!/usr/bin/env sh\nset -eu\ncd "$(dirname "$0")"\nexec java -jar service.jar run manager.json\n')
  write(z,'start.cmd','@echo off\r\ncd /d "%~dp0"\r\njava -jar service.jar run manager.json\r\n')
 supervisor=out/f'pixelmcmanager-restart-supervisor-{version}.zip'
 with zipfile.ZipFile(supervisor,'w',zipfile.ZIP_DEFLATED) as z:
  for name,p in {**license_files,'README.md':ROOT/'tools/RESTART_SUPERVISOR.md','restart_host.py':ROOT/'tools/restart_host.py'}.items():write(z,name,p.read_bytes())
 for p in [service,supervisor]:
  with zipfile.ZipFile(p) as z:
   assert z.testzip() is None
   assert not any(s in n.lower() for n in z.namelist() for s in ['password.txt','web-accounts','data/','credentials/','.env','sqlite'])
  print(p.name,digest(p))
 with zipfile.ZipFile(files['service.jar']) as z:
  assert 'web/index.html' in z.namelist() and 'cn/pixelmc/dailyfortunes/service/FortuneBackend.class' in z.namelist()
  assert 'org/h2/Driver.class' not in z.namelist()
  assert not any('/forge/' in n and 'dailyfortunes' in n or '/client/' in n and 'dailyfortunes' in n for n in z.namelist())
if __name__=='__main__':main()
