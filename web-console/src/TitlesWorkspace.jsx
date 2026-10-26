import React,{useCallback,useState,useEffect} from 'react';
import {Titles} from './titles/Titles';
import {Players} from './titles/Players';
import {History} from './titles/History';
import {Settings} from './titles/Settings';
import {request} from './transport';
import './titles/module.css';
export function TitlesWorkspace({page,preferences,toast,navigate,setUnsaved}){
  const [state,setState]=useState(null),[revision,setRevision]=useState(0),[density,setDensity]=useState('comfortable'),[historyPlayer,setHistoryPlayer]=useState('');
  const refresh=useCallback(async()=>{const next=await request('/state');setState(next);setRevision(v=>v+1);return next;},[]);
  useEffect(()=>{refresh().catch(e=>toast(e.message));const timer=setInterval(()=>refresh().catch(()=>{}),5000);return()=>clearInterval(timer);},[refresh]);
  if(!state)return <p className="quiet-note">正在连接称号数据…</p>;
  const context={state,writable:!!state.health?.database,refresh,toast,revision,density,setDensity,setUnsaved};
  return <div className="titles-module" data-theme={preferences.theme} data-density={density}>
    {page==='titles'?<Titles context={context}/>:page==='title-players'?<Players context={context} viewHistory={id=>{setHistoryPlayer(id);navigate('title-activity');}}/>:page==='title-activity'?<History context={context} player={historyPlayer} clearPlayer={()=>setHistoryPlayer('')}/>:<Settings context={context}/>}
  </div>;
}
