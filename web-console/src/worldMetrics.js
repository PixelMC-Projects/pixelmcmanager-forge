// Local preview only. Production values must come from the server's telemetry.
export const DISK_REFRESH_MS = 30 * 60 * 1000;
export const SHARED_SAVE_BYTES = 100 * 1024 ** 2;
const WINDOW_MS = 60 * 1000;

export function formatBytes(bytes) {
  if (!Number.isFinite(bytes) || bytes < 0) return '—';
  if (bytes < 1024) return `${bytes.toFixed(0)} B`;
  const index = Math.min(3, Math.floor(Math.log(bytes) / Math.log(1024)));
  return `${(bytes / 1024 ** index).toFixed(index === 1 ? 0 : 2)} ${['B', 'KiB', 'MiB', 'GiB'][index]}`;
}

function dimensionTimes(sample, definitions) {
  if (!Number.isFinite(sample.mspt) || sample.mspt < 0) return definitions.map(() => null);
  // Vary the examples together while retaining a separate, unattributed server cost.
  const weights = definitions.map((world, index) => world.tickWeight * (1 + Math.sin(sample.at / 6000 + index) * .045));
  const totalWeight = weights.reduce((sum, weight) => sum + weight, 0);
  const dimensionFraction = .83 + Math.max(0, Math.min(.1, (sample.mspt - 30) / 900));
  return weights.map(weight => totalWeight > 0 ? sample.mspt * dimensionFraction * weight / totalWeight : 0);
}

export function worldPerformance(history, definitions) {
  const latest = history.at(-1);
  if (!latest) return definitions.map(() => ({tickMs: null, tickShare: null, mean60: null, p95Seconds60: null, maxSeconds60: null, sampleCount: 0}));
  const rows = history.filter(sample => sample.at > latest.at - WINDOW_MS && sample.at <= latest.at).map(sample => dimensionTimes(sample, definitions));
  const current = dimensionTimes(latest, definitions);
  return definitions.map((world, index) => {
    const values = rows.map(row => row[index]).filter(Number.isFinite).sort((a, b) => a - b);
    return {
      tickMs: current[index],
      tickShare: latest.mspt > 0 && current[index] != null ? current[index] / latest.mspt * 100 : null,
      mean60: values.length ? values.reduce((sum, value) => sum + value, 0) / values.length : null,
      // This prototype has one-second mean samples, not individual tick percentiles.
      p95Seconds60: values.length ? values[Math.ceil(values.length * .95) - 1] : null,
      maxSeconds60: values.length ? values.at(-1) : null,
      sampleCount: values.length,
    };
  });
}

export function storageSnapshot(definition, at, initialAt) {
  const firstScanAt = initialAt - 2 * 60 * 1000;
  const revision = Math.max(0, Math.floor((at - firstScanAt) / DISK_REFRESH_MS));
  const source = definition.storage;
  const terrainBytes = source.terrainBytes + revision * 1536 * 1024;
  return {
    ...source, terrainBytes,
    totalBytes: terrainBytes + source.entityBytes + source.poiBytes + source.otherBytes,
    measuredAt: firstScanAt + revision * DISK_REFRESH_MS,
  };
}
