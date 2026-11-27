import { useSyncExternalStore } from 'react';
import { PREVIEW_ARCHIVES, PREVIEW_INSTANCE_ID, getPreviewArchive, getPreviewHistoricalSnapshot } from './platformFixtures';

const listeners = new Set();
let selectedArchive = '';
const extraExtensions = new Map();
const extensions = [
  { id: 'memorialtitles', preference: 'titles', name: 'MemorialTitles', label: '称号管理', capabilities: ['称号档案', '玩家收藏', '操作记录'], page: 'titles', enabled: true, healthy: true, storageReady: true, gameConnected: true, description: '纪念称号与玩家收藏 · 本地示例', titles: 6, players: 24 },
  { id: 'dailyfortunes', preference: 'fortunes', name: 'DailyFortunes', label: '抽签管理', capabilities: ['签文档案', '玩家签历', '操作记录'], page: 'fortunes', enabled: true, healthy: true, storageReady: true, gameConnected: true, description: '每日一签与永久签历 · 本地示例' },
];

function scope(path) {
  const url = new URL(path, 'http://local-preview');
  url.searchParams.set('instance', PREVIEW_INSTANCE_ID);
  url.searchParams.set('archive', selectedArchive);
  return url.pathname + url.search;
}

function createPlatform() {
  const pack = getPreviewArchive(selectedArchive);
  return {
    session: { username: '本地管理员', player: 'df000000-0000-4000-a000-000000000001' },
    archive: selectedArchive, instance: PREVIEW_INSTANCE_ID, scope, chooseArchive, chooseInstance, refresh, logout: () => {}, error: '',
    command: async () => { throw new Error(selectedArchive ? '历史档案仅供查看。' : '本地预览 · 游戏指令由正式服务执行。'); },
    value: {
      lastBackup: null, maintenanceError: '', archives: PREVIEW_ARCHIVES,
      instances: [{ id: PREVIEW_INSTANCE_ID, archiveId: PREVIEW_ARCHIVES[0].id, worldId: PREVIEW_ARCHIVES[0].worldId, stopped: false }],
      extensions: [...extensions, ...extraExtensions.values()],
      state: { ...(getPreviewHistoricalSnapshot(selectedArchive) || { pack, historical: false, connected: true }),
        instanceId: PREVIEW_INSTANCE_ID, archiveId: pack.id, activeArchiveId: PREVIEW_ARCHIVES[0].id },
    },
  };
}

let platform = createPlatform();
export const getPreviewPlatform = () => platform;
const subscribe = listener => { listeners.add(listener); return () => listeners.delete(listener); };
export const usePlatform = () => useSyncExternalStore(subscribe, getPreviewPlatform, getPreviewPlatform);

function publish() {
  platform = createPlatform();
  for (const listener of listeners) listener();
}

function chooseArchive(id) {
  if (id && !PREVIEW_ARCHIVES.some(archive => archive.id === id)) return;
  const next = id === PREVIEW_ARCHIVES[0].id ? '' : id;
  if (next === selectedArchive) return;
  selectedArchive = next;
  publish();
}

function chooseInstance(id) {
  if (id !== PREVIEW_INSTANCE_ID) return;
  chooseArchive('');
}

function refresh() { publish(); }

export function registerPreviewExtension(extension) {
  if (!extension || extraExtensions.has(extension.name)) return;
  extraExtensions.set(extension.name, { id: extension.name.toLowerCase(), preference: extension.preference || 'hub', name: extension.name, label: extension.label,
    page: extension.defaultPage, capabilities: extension.nav.map(row => row[1]), enabled: true, healthy: true,
    storageReady: extension.connected ?? true, gameConnected: extension.connected ?? true, description: extension.description || '玩家手册与服务器公开信息 · 本地示例' });
  publish();
}
