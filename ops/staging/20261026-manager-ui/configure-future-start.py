from pathlib import Path
import copy,hashlib,json,os,runpy,shlex,stat,subprocess
G=Path('/home/rhymc/dockerserver/mc-server-1');W=Path('/home/rhymc/dockerserverdata/pixelmc-manager')
GS=G/'pixelmcmanager/deploy-manager-ui-0.5.2-20261026';WS=W/'deployment/manager-ui-0.5.2-20261026'
GI='6ef85649e42a4b19be35e22d55e8c8e0';WI='e4f28a43dc284b9291efde888c081e11'
os.umask(0o077)
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def state():
 return {n:{k:json.loads(subprocess.check_output(['docker','inspect',n]))[0]['State'][k] for k in ['Running','Pid','StartedAt']} for n in ['MCSM-6ef856','pixelmc-manager-web']}
def protected(p,value):p.write_text(json.dumps(value,indent=2)+'\n');p.chmod(0o600)
assert subprocess.run(['systemctl','is-active','pixelmc-deploy-framed-20261026.service'],capture_output=True,text=True).stdout.strip()=='inactive'
assert GS.is_dir() and WS.is_dir()
before=state();active={str(p):sha(p) for p in [G/'mods/pixelmcmanager-forge-1.20.1-0.5.1.jar',G/'automodpack/automodpack-server.json',G/'automodpack/host-modpack/automodpack-content.json',W/'releases/0.5.1/pixelmc-manager-service.jar']}
for stage in [GS,WS]:subprocess.run(['sha256sum','--check','SHA256SUMS'],cwd=stage,check=True,stdout=subprocess.DEVNULL)
for script in [GS/'activate-manager-ui.sh',GS/'startup-manager-ui.sh',WS/'launch-manager.sh']:subprocess.run(['bash','-n',str(script)],check=True)
assert int.from_bytes((GS/'classes/ManagerUiReadiness.class').read_bytes()[6:8],'big')==61
assert (G/'pixelmcmanager/deploy-titles-ui-0.4.2-20261026/activate-titles-ui.sh').is_file()
module=runpy.run_path('/opt/pixelmcmanager/restart_host.py');client=module['DaemonClient']('/opt/mcsmanager/daemon/data/Config/global.json')
activator=G/'pixelmcmanager/activate-manager.sh';old_script=activator.read_text()
try:
 gc=client.request('instance/detail',{'instanceUuid':GI})['data']['config'];wc=client.request('instance/detail',{'instanceUuid':WI})['data']['config']
 assert gc['cwd']==str(G) and wc['cwd']==str(W)
 protected(GS/'game-instance.before.json',gc);protected(WS/'web-instance.before.json',wc)
 (GS/'activate-manager.before-staging.sh').write_text(old_script)
 old_argv=shlex.split(wc['startCommand']);assert old_argv[:2]==['bash','deployment/reminder-20261025/launch-manager.sh']
 argv=old_argv[2:];assert argv[argv.index('-jar')+1]=='releases/0.5.1/pixelmc-manager-service.jar' and argv[-2:]==['run','manager.json']
 new=copy.deepcopy(wc);new['startCommand']='bash deployment/manager-ui-0.5.2-20261026/launch-manager.sh '+shlex.join(argv)
 assert shlex.split(new['startCommand'])[2:]==argv
 needle='exec "$@"\n';assert old_script.count(needle)==1 and 'deploy-manager-ui-0.5.2-20261026/startup-manager-ui.sh' not in old_script
 hook='# Pending Manager/Titles production UI; previous reminder and FramedBlocks hooks stay first.\nif [[ -f "$root/pixelmcmanager/deploy-manager-ui-0.5.2-20261026/startup-manager-ui.sh" ]]; then\n  bash "$root/pixelmcmanager/deploy-manager-ui-0.5.2-20261026/startup-manager-ui.sh"\nfi\n'
 temporary=activator.with_name('.activate-manager.ui.new');temporary.write_text(old_script.replace(needle,hook+needle));temporary.chmod(stat.S_IMODE(activator.stat().st_mode));subprocess.run(['bash','-n',str(temporary)],check=True);os.replace(temporary,activator)
 try:client.request('instance/update',{'instanceUuid':WI,'config':new})
 except Exception:
  client.request('instance/update',{'instanceUuid':WI,'config':wc});activator.write_text(old_script);raise
 saved=client.request('instance/detail',{'instanceUuid':WI})['data']['config'];assert saved['startCommand']==new['startCommand'];assert [k for k in set(wc)|set(saved) if wc.get(k)!=saved.get(k)]==['startCommand']
 assert client.request('instance/detail',{'instanceUuid':GI})['data']['config']==gc
 raw=json.loads((Path('/opt/mcsmanager/daemon/data/InstanceConfig')/(WI+'.json')).read_text());assert raw.get('config',raw)['startCommand']==new['startCommand']
finally:client.close()
for name,script in [('MCSM-6ef856','/data/pixelmcmanager/deploy-manager-ui-0.5.2-20261026/startup-manager-ui.sh'),('pixelmc-manager-web','/data/deployment/manager-ui-0.5.2-20261026/launch-manager.sh')]:
 subprocess.run(['docker','exec',name,'bash',script,'--check-payload'],check=True,stdout=subprocess.DEVNULL)
assert state()==before
assert all(sha(Path(p))==value for p,value in active.items())
report={'status':'staged-for-next-normal-start','gameStage':str(GS),'webStage':str(WS),'before':before,'after':state(),'activeFilesUnchanged':True,'gameInstanceConfigUnchanged':True,'webOnlyFutureStartCommandChanged':True,'jvmArgumentsPreserved':True,'runtimeReadinessProbeExecuted':False,'backupExecuted':False,'restartRequested':False,'dailyFortunesDeployed':False,'pausedOldExecutor':'pixelmc-deploy-framed-20261026.service','hookOrder':['previous reminder','FramedBlocks','Manager 0.5.2 after authenticated backend gate','MemorialTitles 0.4.2-dev after Manager applied'],'webFirstRequired':True}
protected(GS/'staging-report.json',report);protected(WS/'staging-report.json',report);print(json.dumps(report))
