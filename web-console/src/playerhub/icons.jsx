// Fixed Lucide 0.468.0 geometry, ISC. See public/licenses/Lucide-LICENSE.txt.
import React from 'react';
import paths from './iconPaths.json';
export function HubIcon({name,size=24,...props}) { return <svg xmlns="http://www.w3.org/2000/svg" width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" {...props}>{(paths[name]||[]).map(([tag,attrs],i)=>React.createElement(tag,{...attrs,key:i}))}</svg>; }
export const CalendarDays=props=><HubIcon name="calendar-days" {...props}/>;
export const Pin=props=><HubIcon name="pin" {...props}/>;
