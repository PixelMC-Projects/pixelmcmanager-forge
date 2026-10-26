from pathlib import Path
import datetime,hashlib,json,os,runpy,subprocess,time,uuid

GAME=Path('/home/rhymc/dockerserver/mc-server-1')
APP=Path('/home/rhymc/dockerserverdata/pixelmc-manager')
STAGE=GAME/'pixelmcmanager/deploy-manager-ui-0.5.2-20261026'
GAME_ID='6ef85649e42a4b19be35e22d55e8c8e0';WEB_ID='e4f28a43dc284b9291efde888c081e11'
BASELINE='2026-10-25T03:25:27.741595875Z'

def atomic(path,text):
    path.parent.mkdir(parents=True,exist_ok=True)
    temp=path.with_name(path.name+'.tmp');temp.write_text(text);temp.chmod(0o600);os.replace(temp,path)

def gate():
    result=subprocess.run(['docker','exec','MCSM-6ef856','java','-Xms16m','-Xmx64m','-cp',
      '/data/pixelmcmanager/deploy-manager-ui-0.5.2-20261026/classes:/data/libraries/com/google/code/gson/gson/2.10/gson-2.10.jar',
      'ManagerUiReadiness','/data/config/pixelmcmanager-bridge.json'],capture_output=True,timeout=12)
    return result.returncode==0

def main():
    os.umask(0o077)
    request=STAGE/'startup-request.json'
    if not request.exists() or request.stat().st_size>1024:return
    data=json.loads(request.read_text());nonce=str(uuid.UUID(data['nonce']));created=int(data['createdAt'])
    response=STAGE/'responses'/nonce
    if response.exists():return
    report={'nonce':nonce,'phase':'deferred','webCommandRequested':False,'databaseRestartRequested':False}
    try:
        now=time.time()
        if not now-150<=created<=now+5:raise ValueError('stale request')
        state=json.loads(subprocess.check_output(['docker','inspect','MCSM-6ef856']))[0]['State']
        if not state['Running'] or state['StartedAt']==BASELINE:raise ValueError('not a new game container')
        started=datetime.datetime.fromisoformat(state['StartedAt'][:19]+'+00:00').timestamp()
        if created<started-2:raise ValueError('request predates container')
        processes=subprocess.check_output(['docker','top','MCSM-6ef856','-eo','comm,args'],text=True)
        for line in processes.splitlines()[1:]:
            fields=line.strip().split(None,1)
            if len(fields)==2 and fields[0].split('/')[-1]=='java' and any(x in fields[1] for x in ['@libraries/net/minecraftforge/','net.minecraft','net.minecraftforge']):
                raise ValueError('game JVM is already running')
        report['gameStartedAt']=state['StartedAt']
        if gate():
            report['phase']='ready';atomic(response,'ready\n');return
        module=runpy.run_path('/opt/pixelmcmanager/restart_host.py')
        client=module['DaemonClient']('/opt/mcsmanager/daemon/data/Config/global.json')
        try:
            game=client.request('instance/detail',{'instanceUuid':GAME_ID})['data']
            web=client.request('instance/detail',{'instanceUuid':WEB_ID})['data']
            if game['config']['cwd']!=str(GAME) or web['config']['cwd']!=str(APP):raise ValueError('instance binding mismatch')
            if 'deployment/manager-ui-0.5.2-20261026/launch-manager.sh' not in web['config']['startCommand']:raise ValueError('future launcher mismatch')
            previous=json.loads((STAGE/'coordination-report.json').read_text()) if (STAGE/'coordination-report.json').exists() else {}
            already_requested=previous.get('nonce')==nonce and previous.get('webCommandRequested') is True
            if already_requested:report.update(webCommandRequested=True,webAction=previous.get('webAction','uncertain'))
            elif web['status'] in [0,3]:
                action='instance/restart' if web['status']==3 else 'instance/open'
                report.update(phase='web-preparation-requested',webCommandRequested=True,webAction=action)
                atomic(STAGE/'coordination-report.json',json.dumps(report,indent=2)+'\n')
                client.request(action,{'instanceUuid':WEB_ID})
            elif web['status'] not in [1,2]:raise ValueError('web instance state unavailable')
        finally:client.close()
        deadline=time.monotonic()+120
        while time.monotonic()<deadline:
            current=json.loads(subprocess.check_output(['docker','inspect','MCSM-6ef856']))[0]['State']
            if not current['Running'] or current['StartedAt']!=state['StartedAt']:raise ValueError('game startup changed')
            if gate():report['phase']='ready';atomic(response,'ready\n');return
            time.sleep(2)
        raise ValueError('readiness timeout')
    except Exception:
        report['phase']='deferred';atomic(response,'deferred\n')
    finally:
        atomic(STAGE/'coordination-report.json',json.dumps(report,indent=2)+'\n')
        print('Future startup preparation: '+report['phase']+'; web command requested: '+str(report['webCommandRequested']))

if __name__=='__main__':main()
