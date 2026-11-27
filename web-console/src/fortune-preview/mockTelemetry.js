import { useTelemetry as usePrototype, onlinePlayerExamples, population } from '../prototypeTelemetry';
import { usePlatform } from './mockPlatform';
import { getPreviewArchive, getPreviewHistoricalSnapshot, getPreviewPlayers } from './platformFixtures';
export { formatDate, formatPlayTime, uptime, playerExamples } from '../prototypeTelemetry';

export function useTelemetry() {
  const telemetry = usePrototype();
  const platform = usePlatform();
  const current = { ...telemetry, platform, pack: getPreviewArchive(''), historical: false,
    totals: { players: getPreviewPlayers('').length }, players: onlinePlayerExamples, population,
    maxHeap: 13, collections: [], minecraft: '1.20.1', loader: 'Forge', loaderVersion: '47.3.33', java: '17',
    modCount: 328, environment: '本地模拟', runId: 'local-demo', limit: 20, management: null };
  const historical = platform.archive ? getPreviewHistoricalSnapshot(platform.archive) : null;
  return historical ? { ...current, ...historical, platform } : current;
}
