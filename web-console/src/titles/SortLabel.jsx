import React from 'react';
import {ArrowUp,ArrowDown,ArrowsDownUp} from '@phosphor-icons/react';
export function SortLabel({label,field,sort,onChange}){
 const active=sort.key===field,Icon=active?(sort.direction==='asc'?ArrowUp:ArrowDown):ArrowsDownUp;
 return <button className="column-sort" aria-label={'按'+label+'排序'} aria-pressed={active} title={active?(sort.direction==='asc'?'当前升序，点击降序':'当前降序，点击升序'):'点击升序排序'} onClick={()=>onChange({key:field,direction:active&&sort.direction==='asc'?'desc':'asc'})}>{label}<Icon size={12} aria-hidden="true"/></button>;
}
