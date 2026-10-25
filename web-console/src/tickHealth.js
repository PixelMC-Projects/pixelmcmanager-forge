export function detectTickHealth(history,stale=false) {
  const latest=history.at(-1);
  if(stale||!latest||!Number.isFinite(latest.mspt)||!Number.isFinite(latest.tps))return {state:'stale',label:'未更新',reason:'没有有效的新样本，暂不判断性能。'};
  const recent=history.filter(sample=>sample.at>latest.at-10500),continuous=recent.length>=10&&recent.at(-1).at-recent[0].at>=9000&&recent.every((sample,index)=>sample.mspt>50&&sample.tps<19.5&&(!index||sample.at-recent[index-1].at<=1500));
  if(continuous)return {state:'insufficient',label:'性能不足',reason:'约 10 秒连续样本超过 50 MSPT，且 TPS 低于 19.5，当前负载已造成持续掉刻。'};
  if(latest.mspt>=40)return {state:'risk',label:'有性能风险',reason:'当前 MSPT 达到 40 ms，或高耗时尚未持续足够时间；短时尖峰不直接判为性能不足。'};
  return {state:'safe',label:'性能充足',reason:'当前负载下 MSPT 低于 40 ms；不代表可以保证任何额外负载都能承受。'};
}
