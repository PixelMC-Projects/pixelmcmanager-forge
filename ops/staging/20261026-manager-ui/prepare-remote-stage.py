from pathlib import Path
import hashlib,json,os,shutil,subprocess
G=Path('/home/rhymc/dockerserver/mc-server-1');W=Path('/home/rhymc/dockerserverdata/pixelmc-manager')
GS=G/'pixelmcmanager/deploy-manager-ui-0.5.2-20261026';WS=W/'deployment/manager-ui-0.5.2-20261026';incoming=Path('/tmp/pixelmc-manager-ui-0.5.2-20261026')
os.umask(0o077)
assert not GS.exists() and not WS.exists(),'Existing stage requires inspection, never overwrite it'
expected={'game/pixelmcmanager-forge-1.20.1-0.5.2.jar':'71bd6bc6d915df392c8223c961d2d793a9bd6d0a6b0ec6ebe229a7a7fe518a27','web/pixelmc-manager-service-0.5.2.jar':'4ce5d55f90a0bb9c079e996acce259175ca1bacc6e68f7fa277a74e3ffeddf82'}
for rel,digest in expected.items():assert hashlib.sha256((incoming/rel).read_bytes()).hexdigest()==digest,rel
for role in ['game','web']:subprocess.run(['sha256sum','--check','SHA256SUMS'],cwd=incoming/role,check=True,stdout=subprocess.DEVNULL)
for name,pid in [('MCSM-6ef856',1473227),('pixelmc-manager-web',1472134)]:
 state=json.loads(subprocess.check_output(['docker','inspect',name]))[0]['State'];assert state['Running'] and state['Pid']==pid,'Instance changed independently; inspect first'
for src,target in [(incoming/'game',GS),(incoming/'web',WS)]:
 shutil.copytree(src,target)
 for p in [target,*target.rglob('*')]:p.chmod(0o700 if p.is_dir() else 0o600)
 (target/'DEPLOYMENT-NOTES.md').write_text('Staged for the next normal restart only. No runtime probe, database write, backup, start, stop or restart was executed during staging. Restart the web instance first; it takes a native pre-upgrade backup, then runs 0.5.2. At a subsequent game startup the old reminder and FramedBlocks hooks run first; authenticated API readiness gates Manager 0.5.2 and MemorialTitles 0.4.2-dev. DailyFortunes is not deployed or enabled. Restore the saved future-start command and original activator to cancel before activation; after activation, stop writers and use a verified pre-upgrade backup for any database downgrade.\n')
print(json.dumps({'gameStage':str(GS),'webStage':str(WS),'payloadSha256Verified':expected,'runningInstancesUnchanged':True}))
