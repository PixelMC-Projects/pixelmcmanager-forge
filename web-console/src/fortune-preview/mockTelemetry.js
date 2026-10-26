import {useTelemetry as usePrototype,allPlayerExamples,onlinePlayerExamples,population} from '../prototypeTelemetry';
import {usePlatform} from './mockPlatform';
export {formatDate,formatPlayTime,uptime,playerExamples} from '../prototypeTelemetry';
export function useTelemetry(){const t=usePrototype();return {...t,platform:usePlatform(),pack:{name:'香草纪元',version:'本地预览'},totals:{players:allPlayerExamples.length},players:onlinePlayerExamples,population,maxHeap:13,collections:[],minecraft:'1.20.1',loader:'Forge',loaderVersion:'47.3.33',java:'17',modCount:328,environment:'本地模拟',runId:'local-demo',limit:20,management:null};}
