import React, { useEffect, useRef, useState } from 'react';
import uPlot from 'uplot';
import 'uplot/dist/uPlot.min.css';
import { reduceNoise } from './trendData';

/** Real canvas chart. One shared telemetry history; no per-widget polling. */
export function Trend({ history, field, theme, detailed = false, muted = false }) {
  const host = useRef(null), chart = useRef(null), latest = useRef(history), observer = useRef(null);
  const [hover, setHover] = useState(null);
  latest.current = history;
  const data = entries => { const drawn = reduceNoise(entries, field); return [drawn.map(s => s.at / 1000), drawn.map(s => s[field])]; };
  useEffect(() => {
    const element = host.current;
    const color = field === 'tps' ? (theme === 'light' ? '#368b70' : '#80c7ad') : field === 'mspt' ? (theme === 'light' ? '#8270bf' : '#b4a2ec') : (theme === 'light' ? '#6d7baf' : '#9aa6d0');
    const rect = element.getBoundingClientRect();
    const mutedColor = theme === 'light' ? '#8c8b93' : '#74747d';
    const grid = theme === 'light' ? '#eeedf1' : '#252529';
    chart.current = new uPlot({
      width: Math.max(100, Math.floor(rect.width)), height: Math.max(70, Math.floor(rect.height)),
      pxAlign: 1, padding: detailed ? [12, 8, 0, 0] : [8, 0, 8, 0],
      legend: { show: false }, select: { show: false },
      cursor: { show: true, y: false, points: { show: false } },
      scales: { x: { time: true }, y: { range: field === 'tps' ? [0, 20.5] : field === 'mspt' ? [0, 135] : [0, 13] } },
      axes: detailed ? [
        { stroke: mutedColor, grid: { show: false }, ticks: { show: false }, font: '10px Inter, sans-serif', size: 32, values: (u, ticks) => ticks.map(t => new Date(t * 1000).toLocaleTimeString('zh-CN', { hour12: false, minute: '2-digit', second: '2-digit' })) },
        { stroke: mutedColor, grid: { stroke: grid, width: 1 }, ticks: { show: false }, font: '10px Inter, sans-serif', size: 36, gap: 8 },
      ] : [{ show: false }, { show: false }],
      series: [{}, { stroke: color, width: detailed ? 1.8 : 1.6, paths: uPlot.paths.spline(), points: { show: false }, fill: detailed ? color + '08' : undefined }],
      hooks: { setCursor: [plot => {
        const index = plot.cursor.idx;
        if (index == null) { setHover(null); return; }
        const at = plot.data[0][index], value = plot.data[1][index];
        if (at != null && value != null) setHover(new Date(at * 1000).toLocaleTimeString('zh-CN', { hour12: false }) + ' · ' + value.toFixed(field === 'tps' ? 2 : 1) + (field === 'mspt' ? ' ms' : field === 'heap' ? ' GB' : ' TPS'));
      }] },
    }, data(latest.current), element);
    observer.current = new ResizeObserver(entries => {
      const size = entries[0].contentRect;
      if (chart.current && size.width > 0 && size.height > 0) chart.current.setSize({ width: Math.floor(size.width), height: Math.floor(size.height) });
    });
    observer.current.observe(element);
    return () => { observer.current?.disconnect(); chart.current?.destroy(); chart.current = null; };
  }, [field, detailed, theme]);
  useEffect(() => {
    if (chart.current && document.visibilityState !== 'hidden') chart.current.setData(data(history));
  }, [history, field]);
  return <div className={`trend-wrap ${muted ? 'trend-muted' : ''}`} role="img" aria-label={`${field === 'tps' ? 'TPS' : field === 'mspt' ? 'MSPT' : '堆内存'}最近五分钟模拟趋势`}><div ref={host} className="trend-canvas" />{hover && <span className="trend-tooltip">{hover}</span>}</div>;
}
