import React,{useCallback,useEffect,useState} from 'react';
import {api} from './fortune/api';
import {FortuneArchive,PlayerCalendars,OperationHistory,FortuneSettings} from './fortune/pages';
import {ErrorNotice} from './titles/ui';
import './titles/module.css';
import './fortune/module.css';
export function FortuneWorkspace({page,preferences,toast,setUnsaved}){
 const [state,setState]=useState(null),[error,setError]=useState(''),[revision,setRevision]=useState(0);
 const refresh=useCallback(async()=>{try{const next=await api('/state');setState(next);setError('');setRevision(v=>v+1);return next;}catch(e){setError(e.message);throw e;}},[]);
 useEffect(()=>{refresh().catch(()=>{});const timer=setInterval(()=>refresh().catch(()=>{}),10000);return()=>clearInterval(timer);},[refresh]);
 const context={state,refresh,revision,toast,setUnsaved,writable:!!state?.health?.database&&!error};
 return <div className="titles-module fortunes-module" data-theme={preferences.theme}><ErrorNotice error={error}/>{!state?<p className="quiet-note">{error?'抽签数据当前不可用，未使用模拟数据。':'正在连接抽签数据…'}</p>:<>{!state.health?.database&&<p className="form-error" role="status">抽签数据库暂不可用，修改已停用。</p>}{page==='fortunes'?<FortuneArchive context={context}/>:page==='fortune-players'?<PlayerCalendars context={context}/>:page==='fortune-activity'?<OperationHistory context={context}/>:<FortuneSettings context={context}/>}</>}</div>;
}
