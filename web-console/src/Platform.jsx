import React,{createContext,useContext,useEffect,useState} from 'react';
import {request,setCsrf,newId} from './transport';
import {App} from './App';
const Context=createContext(null);
export const usePlatform=()=>useContext(Context);
export function Platform({session,logout}){
  const [instance,setInstance]=useState(()=>sessionStorage.getItem('pixelmc-instance')||''),[archive,setArchive]=useState(''),[value,setValue]=useState(null),[error,setError]=useState('');
  const [revision,refresh]=useState(0);
  const query=new URLSearchParams({instance,archive}).toString();
  useEffect(()=>{let active=true,source,timer;setError('');
    const load=async()=>{try{const next=await request('/manager/bootstrap?'+query);if(!active)return;setValue(next);setError('');if(!instance&&next.instances?.length){setInstance(next.instances[0].id);return;}
      source=new EventSource('/api/manager/events?'+query);source.addEventListener('snapshot',e=>{if(active){try{setValue(JSON.parse(e.data));setError('');}catch{setError('实时数据格式无效');}}});source.onerror=()=>{if(active)setError('实时连接暂不可用，正在重连');};
    }catch(e){if(active){setError(e.message);timer=setTimeout(load,5000);}}};load();return()=>{active=false;source?.close();clearTimeout(timer);};
  },[query,revision,instance]);
  const chooseInstance=id=>{sessionStorage.setItem('pixelmc-instance',id);setInstance(id);setArchive('');setValue(null);};
  const scope=path=>path+(path.includes('?')?'&':'?')+query;
  const command=async(op,payload={})=>{if(!value?.state?.connected||value.state.historical)throw new Error('当前游戏未连接或正在查看只读档案');
    const result=await request(scope('/manager/command'),{requestId:newId(),archiveId:archive,runId:value.state.runId,op,payload});
    const end=Date.now()+32000;while(Date.now()<end){await new Promise(r=>setTimeout(r,1000));const ack=await request(scope('/manager/command?id='+encodeURIComponent(result.id)));if(ack.state==='done'){refresh(n=>n+1);return ack.result||'操作完成';}if(ack.state==='failed'||ack.state==='expired')throw new Error(ack.result||'操作未执行或已过期');}throw new Error('尚未收到执行结果，请刷新状态核实，勿重复提交');};
  return <Context.Provider value={{session,logout,instance,archive,chooseInstance,chooseArchive:setArchive,value,error,scope,command,refresh:()=>refresh(n=>n+1)}}><App/></Context.Provider>;
}
export function AuthRoot(){
  const [session,setSession]=useState(null),[loading,setLoading]=useState(true),[error,setError]=useState(''),[pending,setPending]=useState(false);
  useEffect(()=>{let active=true;request('/me').then(s=>{if(active){setCsrf(s.csrf);setSession(s);}}).catch(e=>{if(active&&e.status!==401)setError(e.message);}).finally(()=>{if(active)setLoading(false);});const expire=()=>{setCsrf('');setSession(null);};window.addEventListener('manager-session-expired',expire);return()=>{active=false;window.removeEventListener('manager-session-expired',expire);};},[]);
  if(loading)return <div className="auth-page"><p>正在连接 PixelMC Manager…</p></div>;
  if(session)return <Platform session={session} logout={async()=>{try{await request('/logout',{});}finally{setCsrf('');setSession(null);}}}/>;
  return <div className="auth-page" data-theme="dark"><form className="auth-card" onSubmit={async e=>{e.preventDefault();if(pending)return;const form=new FormData(e.currentTarget);setPending(true);setError('');try{const s=await request('/login',{username:form.get('username'),password:form.get('password')});setCsrf(s.csrf);setSession(s);}catch(e){setError(e.message);}finally{setPending(false);}}}><span className="auth-brand">PixelMC Manager</span><h1>登录工作空间</h1><p>服务器数据与扩展管理</p><label>用户名<input name="username" autoComplete="username" required maxLength={64}/></label><label>密码<input name="password" type="password" autoComplete="current-password" required maxLength={256}/></label>{error&&<p className="form-error" role="alert">{error}</p>}<button className="button primary" disabled={pending}>{pending?'正在登录…':'登录'}</button></form></div>;
}
