import {request} from '../transport';
export {newId} from '../transport';
export const api=(path,body,options)=>request('/dailyfortunes'+path,body,options);
export const qs=values=>'?'+new URLSearchParams(Object.entries(values).filter(([,v])=>v!==undefined&&v!==''));
export function saveText(filename,text,type='application/json'){
 const url=URL.createObjectURL(new Blob([text],{type}));const link=document.createElement('a');link.href=url;link.download=filename;link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
}
export async function download(path,fallback){const value=await api(path);saveText(value.filename||value.name||fallback,value.text??JSON.stringify(value,null,2));}
