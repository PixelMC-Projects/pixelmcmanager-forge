from pathlib import Path
import hashlib,json,os,shutil,stat,subprocess
G=Path('/home/rhymc/dockerserver/mc-server-1');W=Path('/home/rhymc/dockerserverdata/pixelmc-manager');S=G/'pixelmcmanager/deploy-manager-ui-0.5.2-20261026'
os.umask(0o077)
def state():
 return {n:{k:json.loads(subprocess.check_output(['docker','inspect',n]))[0]['State'][k] for k in ['Running','Pid','StartedAt']} for n in ['MCSM-6ef856','pixelmc-manager-web']}
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
before=state();assert before['MCSM-6ef856']['Pid']==1473227 and before['pixelmc-manager-web']['Pid']==1472134
paths=[*sorted((G/'mods').glob('memorialtitles*.jar')),*sorted((G/'mods').glob('pixelmcmanager*.jar')),G/'automodpack/automodpack-server.json',G/'automodpack/host-modpack/automodpack-content.json',W/'releases/0.5.1/pixelmc-manager-service.jar'];baseline={str(p):sha(p) for p in paths}
assert not (S/'startup-request.json').exists()
assert not (S/'applied.txt').exists()
service=Path('/etc/systemd/system/pixelmc-manager-ui-startup-0.5.2.service');path=service.with_suffix('.path');helper=Path('/opt/pixelmcmanager/coordinate-manager-ui-0.5.2.py')
assert not service.exists() and not path.exists() and not helper.exists()
shutil.copy2(S/'SHA256SUMS',S/'SHA256SUMS.before-coordinator')
shutil.copyfile('/tmp/coordinate-startup-manager-ui-0.5.2.sh',S/'coordinate-startup.sh');(S/'coordinate-startup.sh').chmod(0o600)
temp=S/'.SHA256SUMS.coordinator.new';shutil.copyfile('/tmp/manager-ui-0.5.2-game-SHA256SUMS',temp);os.replace(temp,S/'SHA256SUMS')
subprocess.run(['sha256sum','--check','SHA256SUMS'],cwd=S,check=True,stdout=subprocess.DEVNULL)
shutil.copyfile('/tmp/coordinate-manager-ui-0.5.2.py',helper);helper.chmod(0o700)
subprocess.run(['python3','-m','py_compile',str(helper)],check=True)
service.write_text('[Unit]\nDescription=Prepare Manager web only for a future new game startup\nAfter=docker.service\n\n[Service]\nType=oneshot\nExecStart=/usr/bin/python3 /opt/pixelmcmanager/coordinate-manager-ui-0.5.2.py\nTimeoutStartSec=180\n')
path.write_text('[Unit]\nDescription=Wait for a future Manager game-startup request\n\n[Path]\nPathChanged='+str(S/'startup-request.json')+'\nUnit='+service.name+'\n\n[Install]\nWantedBy=multi-user.target\n')
service.chmod(0o644);path.chmod(0o644)
subprocess.run(['systemd-analyze','verify',str(service),str(path)],check=True)
activator=G/'pixelmcmanager/activate-manager.sh';old=activator.read_text();(S/'activate-manager.before-coordination.sh').write_text(old)
needle='# Pending title reminder deployment; original JVM arguments remain unchanged.\n';assert old.count(needle)==1
hook='# Coordinate the web backend at a future normal game startup, before the old reminder hook.\nif [[ -f "$root/pixelmcmanager/deploy-manager-ui-0.5.2-20261026/coordinate-startup.sh" ]]; then\n  bash "$root/pixelmcmanager/deploy-manager-ui-0.5.2-20261026/coordinate-startup.sh"\nfi\n'
tmp=activator.with_name('.activate-manager.coordinator.new');tmp.write_text(old.replace(needle,hook+needle));tmp.chmod(stat.S_IMODE(activator.stat().st_mode));subprocess.run(['bash','-n',str(tmp)],check=True);os.replace(tmp,activator)
subprocess.run(['docker','exec','MCSM-6ef856','bash','/data/pixelmcmanager/deploy-manager-ui-0.5.2-20261026/coordinate-startup.sh','--check-payload'],check=True,stdout=subprocess.DEVNULL)
subprocess.run(['systemctl','daemon-reload'],check=True);subprocess.run(['systemctl','enable','--now',path.name],check=True)
unit_state=subprocess.check_output(['systemctl','show',path.name,'--property=ActiveState,SubState'],text=True)
worker_state=subprocess.check_output(['systemctl','show',service.name,'--property=ActiveState,ExecMainStartTimestampMonotonic'],text=True)
assert 'ActiveState=active' in unit_state and 'SubState=waiting' in unit_state
assert 'ActiveState=inactive' in worker_state and 'ExecMainStartTimestampMonotonic=0' in worker_state
assert not (S/'startup-request.json').exists() and not (S/'coordination-report.json').exists()
assert state()==before and all(sha(Path(p))==value for p,value in baseline.items())
report=json.loads((S/'staging-report.json').read_text());report.update(futureGameStartupAutomaticallyCoordinatesWeb=True,manualWebRestartRequired=False,futureCoordinatorPathUnit=path.name,futureCoordinatorPathState='active/waiting',futureWorkerExecuted=False,databaseRestartRequested=False,hookOrder=['future startup web readiness / one normal web prepare if needed','previous reminder','FramedBlocks','Manager 0.5.2','MemorialTitles 0.4.2-dev'],coordinatorLocalSafetyScenarios=8)
report['webFirstRequired']='automatic coordinator handles web first at the future game startup'
for p in [S/'staging-report.json',W/'deployment/manager-ui-0.5.2-20261026/staging-report.json']:p.write_text(json.dumps(report,indent=2)+'\n')
note='The next normal game startup now requests the host coordinator first. Only for that new container before its Forge JVM starts, the host prepares/restarts the existing web instance and waits for authenticated API readiness. Then existing reminder/FramedBlocks hooks and new Manager/Titles activation continue. No manual separate web restart is required. The path listener is waiting; no request or worker was executed during staging. The database is never restarted. On failure or timeout, incompatible upgrades remain staged. To cancel before activation restore activate-manager.before-staging.sh and the saved web-instance.before.json future command, and disable pixelmc-manager-ui-startup-0.5.2.path.\n'
for p in [S/'DEPLOYMENT-NOTES.md',W/'deployment/manager-ui-0.5.2-20261026/DEPLOYMENT-NOTES.md']:p.write_text(note)
print(json.dumps(report))
