import React, {useEffect,useRef,useState} from 'react';
import uPlot from 'uplot';
import 'uplot/dist/uPlot.min.css';
import {metricTone,tickSeries} from './trendData';

export function TickTrend({history,theme,muted=false,compact=false,rangeLabel='近五分钟'}) {
  const host=useRef(null),chart=useRef(null),latest=useRef(history);
  const [hover,setHover]=useState(null);
  latest.current=history;
  useEffect(()=>{
    const element=host.current,light=theme==='light';
    const text=light?'#817e8b':'#85858f',grid=light?'#eeedf1':'#29292d';
    const colors=[light?'#368b70':'#80c7ad',light?'#8270bf':'#b4a2ec'];
    const rect=element.getBoundingClientRect();
    const line=color=>({stroke:color,width:1.8,paths:uPlot.paths.spline(),points:{show:false}});
    chart.current=new uPlot({
      width:Math.max(100,Math.floor(rect.width)),height:Math.max(110,Math.floor(rect.height)),
      padding:[8,8,0,0],legend:{show:false},select:{show:false},cursor:{y:false,points:{show:false}},
      scales:{x:{time:true},y:{range:(plot,min,max)=>[0,Math.max(150,Math.ceil((max||100)*1.08/50)*50)]}},
      axes:[
        {stroke:text,grid:{show:false},ticks:{show:false},font:'10px Inter, sans-serif',size:28,space:compact?100:120,values:(plot,ticks)=>{const span=(latest.current.at(-1)?.at??0)-(latest.current[0]?.at??0);return ticks.map(at=>new Date(at*1000).toLocaleTimeString('zh-CN',{timeZone:'Asia/Shanghai',hour12:false,...(span>1800000?{hour:'2-digit',minute:'2-digit'}:{minute:'2-digit',second:'2-digit'})}));}},
        {stroke:text,grid:{stroke:grid,width:1},ticks:{show:false},font:'10px Inter, sans-serif',size:compact?40:46,gap:8,incrs:[25,50,100,200,500,1000],values:(plot,ticks)=>ticks.map(value=>value+'%')},
      ],
      series:[{},line(colors[0]),line(colors[1])],
      hooks:{setCursor:[plot=>{
        const i=plot.cursor.idx;
        if(i==null){setHover(null);return;}
        const tps=plot.data[1][i],mspt=plot.data[2][i];
        if(tps==null||mspt==null){setHover(null);return;}
        setHover({at:plot.data[0][i]*1000,tps:tps/100*20,mspt:mspt/100*50});
      }]},
    },tickSeries(latest.current),element);
    const observer=new ResizeObserver(([entry])=>{const {width,height}=entry.contentRect;if(width>0&&height>0)chart.current?.setSize({width:Math.floor(width),height:Math.floor(height)});});
    observer.observe(element);
    return ()=>{observer.disconnect();chart.current?.destroy();chart.current=null;};
  },[theme,compact]);
  useEffect(()=>{if(chart.current&&document.visibilityState!=='hidden')chart.current.setData(tickSeries(history));},[history]);
  useEffect(()=>setHover(null),[rangeLabel]);
  return <div className={`tick-trend ${compact?'compact-tick-trend':''} ${muted?'trend-muted':''}`} role="img" aria-label={'TPS与MSPT共用基准百分比刻度的'+rangeLabel+'趋势'}><div className="tick-trend-canvas" ref={host}/>{hover&&!muted&&<div className="tick-trend-tooltip"><time>{new Date(hover.at).toLocaleTimeString('zh-CN',{timeZone:'Asia/Shanghai',hour12:false})}</time><span><i className="legend-dot tps"/>TPS <strong className={'metric-tone-'+metricTone('tps',hover.tps)}>{hover.tps.toFixed(2)}</strong></span><span><i className="legend-dot mspt"/>MSPT <strong className={'metric-tone-'+metricTone('mspt',hover.mspt)}>{hover.mspt.toFixed(1)} ms</strong></span></div>}</div>;
}

export function TickLegend(){return <div className="tick-legend" aria-label="曲线图例"><span><i className="legend-dot tps"/>TPS</span><span><i className="legend-dot mspt"/>MSPT</span></div>;}
export function TickScaleNote(){return <span className="tick-scale-note">基准刻度：20 TPS、50 ms 均为 100%</span>;}
