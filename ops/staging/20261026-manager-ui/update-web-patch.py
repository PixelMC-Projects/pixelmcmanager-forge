"""Replace only the unactivated web payload; never restart or probe production."""
from pathlib import Path
import hashlib,json,os,shutil,subprocess,zipfile
G=Path('/home/rhymc/dockerserver/mc-server-1');W=Path('/home/rhymc/dockerserverdata/pixelmc-manager');S=G/'pixelmcmanager/deploy-manager-ui-0.5.2-20261026';WS=W/'deployment/manager-ui-0.5.2-20261026'
NEW='pixelmc-manager-service-0.5.2-web.1.jar';NEW_SHA='d13c8c8e4817f9b55e56f53eb5070eee63b1d71a6a28a26c4e8399a584b1d1e6';BASE_SHA='4ce5d55f90a0bb9c079e996acce259175ca1bacc6e68f7fa277a74e3ffeddf82'
os.umask(0o077)
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def state():return {n:{k:json.loads(subprocess.check_output(['docker','inspect',n]))[0]['State'][k] for k in ['Running','Pid','StartedAt']} for n in ['MCSM-6ef856','pixelmc-manager-web']}
before=state();assert before['MCSM-6ef856']['Pid']==1473227 and before['pixelmc-manager-web']['Pid']==1472134
assert not (S/'startup-request.json').exists() and not (S/'coordination-report.json').exists() and not (S/'applied.txt').exists()
worker=subprocess.check_output(['systemctl','show','pixelmc-manager-ui-startup-0.5.2.service','--property=ActiveState,ExecMainStartTimestampMonotonic'],text=True);assert 'ActiveState=inactive' in worker and 'ExecMainStartTimestampMonotonic=0' in worker
original=WS/'pixelmc-manager-service-0.5.2.jar';incoming=Path('/tmp')/NEW;assert sha(original)==BASE_SHA and sha(incoming)==NEW_SHA
with zipfile.ZipFile(original) as a,zipfile.ZipFile(incoming) as b:
 names={n for n in a.namelist() if not n.startswith('web/') and n!='META-INF/MANIFEST.MF'}
 assert names=={n for n in b.namelist() if not n.startswith('web/') and n!='META-INF/MANIFEST.MF'}
 assert all(a.read(n)==b.read(n) for n in names);classes=sum(n.endswith('.class') for n in names)
active=[*sorted((G/'mods').glob('pixelmcmanager*.jar')),*sorted((G/'mods').glob('memorialtitles*.jar')),G/'automodpack/automodpack-server.json',G/'automodpack/host-modpack/automodpack-content.json',W/'releases/0.5.1/pixelmc-manager-service.jar'];baseline={str(p):sha(p) for p in active}
backup=WS/'before-web-patch';backup.mkdir(mode=0o700)
for name in ['launch-manager.sh','SHA256SUMS','staging-report.json','DEPLOYMENT-NOTES.md']:shutil.copy2(WS/name,backup/name)
launcher=(WS/'launch-manager.sh').read_text();assert launcher.count('pixelmc-manager-service-0.5.2.jar')==1
launcher=launcher.replace('pixelmc-manager-service-0.5.2.jar',NEW).replace('Starting backend 0.5.2 with','Starting backend 0.5.2-web.1 with')
temp=WS/'.web-patch.jar.tmp';shutil.copyfile(incoming,temp);temp.chmod(0o600);os.replace(temp,WS/NEW)
script=WS/'.launch-manager.patch.tmp';script.write_text(launcher);script.chmod(0o600);subprocess.run(['bash','-n',str(script)],check=True)
names=[line.split('  ',1)[1] for line in (WS/'SHA256SUMS').read_text().splitlines()];names.append(NEW)
checks=''.join((hashlib.sha256(launcher.encode()).hexdigest() if name=='launch-manager.sh' else sha(WS/name))+'  '+name+'\n' for name in names)
check=WS/'.SHA256SUMS.patch.tmp';check.write_text(checks);os.replace(check,WS/'SHA256SUMS');os.replace(script,WS/'launch-manager.sh')
subprocess.run(['sha256sum','--check','SHA256SUMS'],cwd=WS,check=True,stdout=subprocess.DEVNULL)
subprocess.run(['docker','exec','pixelmc-manager-web','bash','/data/deployment/manager-ui-0.5.2-20261026/launch-manager.sh','--check-payload'],check=True,stdout=subprocess.DEVNULL)
assert state()==before and all(sha(Path(p))==value for p,value in baseline.items())
report=json.loads((S/'staging-report.json').read_text());report.update(webPayloadVersion='0.5.2-web.1',webPayloadSha256=NEW_SHA,baseJavaPayloadSha256=BASE_SHA,unchangedJavaClasses=classes,webPatchRuntimeActivated=False)
for p in [S/'staging-report.json',WS/'staging-report.json']:p.write_text(json.dumps(report,indent=2)+'\n')
for p in [S/'DEPLOYMENT-NOTES.md',WS/'DEPLOYMENT-NOTES.md']:
 with p.open('a') as f:f.write('\nThe unactivated web payload is now 0.5.2-web.1 (32px/40px avatar CSS fix). All 619 Java classes and non-web resources remain identical to the immutable 0.5.2 service; original release/tag are unchanged. before-web-patch/ preserves the prior launcher/checksum/report. No running process was changed.\n')
print(json.dumps(report))
