import importlib.util,tempfile,json,uuid,time,datetime
from pathlib import Path
from unittest.mock import patch

source=Path(__file__).with_name('coordinate-future-web.py')
spec=importlib.util.spec_from_file_location('future_coord',source);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
actions=[];calls=[];settings={'baseline':False,'jvm':False,'status':3};ready=[]
new_started=datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%S.000000000Z')
class Client:
 def __init__(self,*a):pass
 def request(self,path,payload):
  if path=='instance/detail':return {'data':{'status':settings['status'],'config':{'cwd':str(m.GAME if payload['instanceUuid']==m.GAME_ID else m.APP),'startCommand':'bash deployment/manager-ui-0.5.2-20261026/launch-manager.sh java'}}}
  actions.append((path,payload));return {'status':200}
 def close(self):pass
def output(cmd,**kw):
 calls.append(cmd)
 if cmd[1]=='inspect':return json.dumps([{'State':{'Running':True,'StartedAt':m.BASELINE if settings['baseline'] else new_started}}]).encode()
 return 'COMMAND ARGS\n'+('java java @libraries/net/minecraftforge/forge/x/unix_args.txt' if settings['jvm'] else 'bash bash activate-manager.sh java @libraries/net/minecraftforge/forge/x/unix_args.txt')
def gate():return ready.pop(0) if ready else False
with tempfile.TemporaryDirectory(prefix='manager-coordinate-') as folder:
 m.STAGE=Path(folder);m.GAME=Path(folder)/'game';m.APP=Path(folder)/'app'
 def setup(values,baseline=False,jvm=False,status=3,prior=False):
  ready[:]=values;settings.update(baseline=baseline,jvm=jvm,status=status);actions.clear();calls.clear();nonce=str(uuid.uuid4());(m.STAGE/'startup-request.json').write_text(json.dumps({'nonce':nonce,'createdAt':int(time.time())}));
  if prior:(m.STAGE/'coordination-report.json').write_text(json.dumps({'nonce':nonce,'webCommandRequested':True,'webAction':'instance/restart'}))
  return nonce
 with patch.object(m.subprocess,'check_output',output),patch.object(m.runpy,'run_path',lambda p:{'DaemonClient':Client}),patch.object(m,'gate',gate),patch.object(m.time,'sleep',lambda n:None):
  m.main();assert not calls and not actions
  n=setup([],baseline=True);m.main();assert not actions and (m.STAGE/'responses'/n).read_text().strip()=='deferred'
  n=setup([],jvm=True);m.main();assert not actions
  n=setup([True]);m.main();assert not actions and (m.STAGE/'responses'/n).read_text().strip()=='ready'
  n=setup([False,False,True]);m.main();assert actions==[('instance/restart',{'instanceUuid':m.WEB_ID})];m.main();assert len(actions)==1
  n=setup([False,True],prior=True);m.main();assert not actions and (m.STAGE/'responses'/n).read_text().strip()=='ready'
  n=setup([False,True],status=0);m.main();assert actions==[('instance/open',{'instanceUuid':m.WEB_ID})]
  n=setup([]);ticks=iter(range(0,1000,20))
  with patch.object(m.time,'monotonic',lambda:next(ticks)):m.main()
  assert len(actions)==1 and (m.STAGE/'responses'/n).read_text().strip()=='deferred'
print('PASS: 8 local future-coordinator safety and delivery scenarios; no real Docker/MCSM call')
