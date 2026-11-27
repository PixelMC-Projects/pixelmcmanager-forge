// Isolated frontend fixtures. These records never query a server or a database.
import { allPlayerExamples, worlds } from '../prototypeTelemetry';
import { sampleStatistics } from '../performanceHistory';

export const PREVIEW_INSTANCE_ID = 'local-preview';
export const PREVIEW_ARCHIVES = [
  { id: 'df100000-0000-4000-a000-000000000001', instanceId: PREVIEW_INSTANCE_ID, worldId: 'df200000-0000-4000-a000-000000000001', name: '香草纪元', version: '2.7.1', startedOn: '2026-10-17', endedAt: null, status: 'active', finalized: false },
  { id: 'df100000-0000-4000-a000-000000000002', instanceId: PREVIEW_INSTANCE_ID, worldId: 'df200000-0000-4000-a000-000000000002', name: '枫林物语', version: '1.6', startedOn: '2026-07-12', endedAt: Date.parse('2026-10-06T22:00:00+08:00'), status: 'archived', finalized: true },
  { id: 'df100000-0000-4000-a000-000000000003', instanceId: PREVIEW_INSTANCE_ID, worldId: 'df200000-0000-4000-a000-000000000003', name: '机械动力', version: '6.0', startedOn: '2026-04-03', endedAt: Date.parse('2026-06-29T21:30:00+08:00'), status: 'archived', finalized: true },
];

const archivedPlayers = Object.fromEntries(PREVIEW_ARCHIVES.slice(1).map((archive, archiveIndex) => [archive.id,
  allPlayerExamples.slice(0, archiveIndex === 0 ? 8 : 5).map((player, index) => ({ ...player,
    isOnline: false, world: null, ping: null, sessionMillis: null, online: null,
    totalOnlineMillis: (archiveIndex === 0 ? 220 + index * 19 : 90 + index * 11) * 3_600_000,
    joinCount: (archiveIndex === 0 ? 48 : 21) + index * 3,
    lastLoginAt: archive.endedAt - (index + 1) * 3_600_000,
    lastSeen: archive.endedAt - index * 180_000,
  })),
]));

export function getPreviewArchive(id = '') {
  const archive = PREVIEW_ARCHIVES.find(item => item.id === (id || PREVIEW_ARCHIVES[0].id));
  if (!archive) throw new Error('该演示档案不可用。');
  return archive;
}

export function getPreviewPlayers(id = '') {
  const archive = getPreviewArchive(id);
  return archive.status === 'active' ? allPlayerExamples : archivedPlayers[archive.id];
}

export function getPreviewLogins(id, playerId) {
  const archive = getPreviewArchive(id);
  const player = getPreviewPlayers(id).find(item => item.id === playerId);
  if (!player) return [];
  const at = archive.endedAt ? player.lastLoginAt : Date.parse('2026-11-06T18:20:00+08:00');
  return [0, 1, 2].map(index => ({ id: `${archive.id}/${playerId}/${index}`, at: at - index * 86_400_000,
    current: archive.status === 'active' && player.isOnline && index === 0 }));
}

export function getPreviewLifetime(playerId) {
  return PREVIEW_ARCHIVES.reduce((total, archive) => {
    const player = getPreviewPlayers(archive.id).find(item => item.id === playerId);
    return { totalOnlineMillis: total.totalOnlineMillis + (player?.totalOnlineMillis || 0), joinCount: total.joinCount + (player?.joinCount || 0) };
  }, { totalOnlineMillis: 0, joinCount: 0 });
}

const historicalSnapshots = Object.fromEntries(PREVIEW_ARCHIVES.slice(1).map((archive, archiveIndex) => {
  const fraction = archiveIndex === 0 ? .62 : .31;
  const savedWorlds = worlds.map(world => ({ ...world, players: 0,
    performance: { tickMs: null, tickShare: null, mean60: null, maxSeconds60: null },
    storage: { ...world.storage, measuredAt: archive.endedAt,
      terrainBytes: Math.round(world.storage.terrainBytes * fraction),
      entityBytes: Math.round(world.storage.entityBytes * fraction),
      poiBytes: Math.round(world.storage.poiBytes * fraction),
      otherBytes: Math.round(world.storage.otherBytes * fraction),
      totalBytes: Math.round((world.storage.terrainBytes + world.storage.entityBytes + world.storage.poiBytes + world.storage.otherBytes) * fraction),
    },
  }));
  const worldTotals = savedWorlds.reduce((total, world) => ({ strong: total.strong + world.strong, weak: total.weak + world.weak,
    entities: total.entities + world.entities, forced: total.forced + world.forced, tickMs: 0,
    storageBytes: total.storageBytes + world.storage.totalBytes }), { strong: 0, weak: 0, entities: 0, forced: 0, tickMs: 0, storageBytes: 0 });
  const players = getPreviewPlayers(archive.id);
  return [archive.id, { pack: archive, historical: true, connected: false, stopped: true, stale: true,
    runId: '', sessionId: `preview-history/${archive.id}`, at: archive.endedAt, now: archive.endedAt, receivedAt: archive.endedAt,
    startedAt: archive.endedAt - 5 * 3_600_000, age: 0,
    worlds: savedWorlds, worldTotals, saveBytes: worldTotals.storageBytes + 100 * 1024 ** 2,
    players: [], population: { online: 0, limit: 20 },
    totals: { players: players.length, totalOnlineMillis: players.reduce((sum, player) => sum + player.totalOnlineMillis, 0), joinCount: players.reduce((sum, player) => sum + player.joinCount, 0) },
    latest: { tps: NaN, mspt: NaN, heap: NaN }, history: [], longTicks: [], collections: [], p95: NaN, maximum: NaN,
    maxHeap: NaN, heapCommitted: NaN, heapAfterGc: NaN,
    heapHealth: { state: 'observing', label: '历史档案', reason: '历史档案保留长期资料，性能数据按当前运行查询。' },
    tickHealth: { state: 'stale', label: '历史档案', reason: '性能数据按当前运行查询。' },
    readPerformance: () => ({ plot: [], stats: sampleStatistics([]), firstAt: archive.endedAt, lastAt: archive.endedAt }),
  }];
}));

export function getPreviewHistoricalSnapshot(id) {
  return historicalSnapshots[getPreviewArchive(id).id] || null;
}
