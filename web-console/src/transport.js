import {useEffect,useState} from 'react';
let csrf='';
export class ApiError extends Error {constructor(message,status){super(message);this.status=status;}}
export function setCsrf(value){csrf=value||'';}
export async function request(path,body,options={}){
  const response=await fetch('/api'+path,{credentials:'same-origin',signal:options.signal,method:body===undefined?'GET':'POST',headers:body===undefined?{}:{'Content-Type':'application/json','X-CSRF-Token':csrf},...(body===undefined?{}:{body:JSON.stringify(body)})});
  if(!response.ok){let error;try{error=await response.json();}catch{}if(response.status===401&&path!=='/login')window.dispatchEvent(new Event('manager-session-expired'));throw new ApiError(error?.error||`请求未完成 (${response.status})`,response.status);}
  return options.raw?response:response.json();
}
export function useResource(path,interval=0){const [result,setResult]=useState({data:null,error:'',loading:true});
  useEffect(()=>{let active=true,timer;const abort=new AbortController();setResult({data:null,error:'',loading:!!path});if(!path)return;
    const read=async()=>{try{const data=await request(path,undefined,{signal:abort.signal});if(active)setResult({data,error:'',loading:false});}catch(e){if(active&&e.name!=='AbortError')setResult(v=>({...v,error:e.message,loading:false}));}finally{if(active&&interval)timer=setTimeout(read,interval);}};read();return()=>{active=false;abort.abort();clearTimeout(timer);};
  },[path,interval]);return result;
}

export function newId(){const b=crypto.getRandomValues(new Uint8Array(16));b[6]=(b[6]&15)|64;b[8]=(b[8]&63)|128;const h=[...b].map(v=>v.toString(16).padStart(2,'0')).join('');return `${h.slice(0,8)}-${h.slice(8,12)}-${h.slice(12,16)}-${h.slice(16,20)}-${h.slice(20)}`;}
