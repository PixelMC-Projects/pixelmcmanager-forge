import {useEffect,useState} from 'react';
import {previewApi} from '../titles/previewStore';
import {allPlayerExamples} from '../prototypeTelemetry';
import {sortPlayers} from '../playerSorting';
export class ApiError extends Error{}
export const setCsrf=()=>{};
export const newId=()=>crypto.randomUUID();
export async function request(path,body,options={}){
  if(!path.startsWith('/'))throw new Error('预览不允许请求外部服务。');
  const u=new URL(path,'http://local-preview');
  if(u.pathname==='/manager/players'){
    const q=u.searchParams,rows=sortPlayers(allPlayerExamples.filter(p=>`${p.name} ${p.id}`.toLowerCase().includes((q.get('q')||'').toLowerCase())&&(q.get('online')!=='true'||p.isOnline)),q.get('sort')||'status',q.get('direction')||'asc');return {rows,total:rows.length};
  }
  if(u.pathname.startsWith('/manager/')){
    if(u.pathname.includes('logins'))return {rows:[],total:0};
    throw new Error('此预览仅演示页面，不连接游戏服务。');
  }
  return previewApi(path,body,options);
}
export function useResource(path){const [state,setState]=useState({data:null,error:'',loading:true});useEffect(()=>{let active=true;if(path)request(path).then(data=>active&&setState({data,error:'',loading:false})).catch(e=>active&&setState({data:null,error:e.message,loading:false}));return()=>{active=false;};},[path]);return state;}
