// Simplify only the drawn line. The raw samples and all statistics stay intact.
// RDP uses vertical error in metric units, preserving short, significant peaks.
export function reduceNoise(samples, field) {
  const tolerance = field === 'tps' ? .12 : field === 'mspt' ? 5 : .02;
  if (samples.length < 3) return samples;
  const keep = new Set([0, samples.length - 1]), pending = [[0, samples.length - 1]];
  while (pending.length) {
    const [first, last] = pending.pop(), a = samples[first], b = samples[last];
    let maximum = tolerance, selected = -1;
    for (let i = first + 1; i < last; i++) {
      const ratio = (samples[i].at - a.at) / (b.at - a.at || 1);
      const expected = a[field] + (b[field] - a[field]) * ratio;
      const distance = Math.abs(samples[i][field] - expected);
      if (distance > maximum) { maximum = distance; selected = i; }
    }
    if (selected !== -1) { keep.add(selected); pending.push([first, selected], [selected, last]); }
  }
  return [...keep].sort((a, b) => a - b).map(index => samples[index]);
}

export function metricTone(field, value, stale = false) {
  if (stale || !Number.isFinite(value)) return 'muted';
  if (field === 'mspt') return value < 30 ? 'good' : value < 40 ? 'caution' : value < 50 ? 'hot' : 'critical';
  if (field === 'tps') return value >= 19.5 ? 'good' : value >= 18 ? 'caution' : value >= 15 ? 'hot' : 'critical';
  return 'muted';
}

// Both series share one timestamp set and one percentage scale. The selected
// samples remain real measurements; no clipping or averaging of major extrema.
export function tickSeries(history) {
  const kept = new Set([...reduceNoise(history, 'tps'), ...reduceNoise(history, 'mspt')].map(sample => sample.at));
  const samples = history.filter(sample => kept.has(sample.at));
  return [samples.map(s => s.at / 1000), samples.map(s => s.tps / 20 * 100), samples.map(s => s.mspt / 50 * 100)];
}
export function latencyTone(value, unavailable = false) {
  if (unavailable || !Number.isFinite(value) || value < 0) return 'muted';
  return value < 75 ? 'good' : value < 150 ? 'caution' : value < 300 ? 'hot' : 'critical';
}

