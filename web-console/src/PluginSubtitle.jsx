import React,{useEffect,useRef,useState} from 'react';

export function PluginSubtitle({name}){
  const previous=useRef(name),sequence=useRef(0);
  const [transition,setTransition]=useState({current:name,outgoing:'',sequence:0,moving:false});
  useEffect(()=>{
    if(previous.current===name)return;
    const outgoing=previous.current;
    previous.current=name;
    const serial=++sequence.current;
    setTransition({current:name,outgoing,sequence:serial,moving:true});
    const timer=setTimeout(()=>setTransition(value=>value.sequence===serial?{...value,outgoing:'',moving:false}:value),360);
    return()=>clearTimeout(timer);
  },[name]);
  return <span className="fp-brand-subtitles" aria-hidden="true">
    {transition.outgoing&&<span key={transition.sequence+'-out'} className={'fp-brand-subtitle fp-brand-subtitle-out'+(!transition.current?' fp-brand-subtitle-return':'')} data-label={transition.outgoing}>{transition.outgoing}</span>}
    {transition.current&&<span key={transition.sequence+'-in'} className={'fp-brand-subtitle'+(transition.moving?' fp-brand-subtitle-in':'')} data-label={transition.current}>{transition.current}</span>}
  </span>;
}
