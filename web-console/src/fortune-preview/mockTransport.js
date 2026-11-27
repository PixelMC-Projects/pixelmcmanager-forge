import { useEffect, useState } from 'react';
import { previewApi } from '../titles/previewStore';
import { sortPlayers } from '../playerSorting';
import { PREVIEW_INSTANCE_ID, getPreviewArchive, getPreviewLifetime, getPreviewLogins, getPreviewPlayers } from './platformFixtures';
export class ApiError extends Error {}
export const setCsrf = () => {};
export const newId = () => crypto.randomUUID();

function paginate(rows, query, defaultSize) {
  const page = Math.max(0, Number.parseInt(query.get('page'), 10) || 0);
  const size = Math.min(100, Math.max(1, Number.parseInt(query.get('size'), 10) || defaultSize));
  return { rows: rows.slice(page * size, (page + 1) * size), total: rows.length, page, size };
}

export async function request(path, body, options = {}) {
  if (!path.startsWith('/')) throw new Error('预览不允许请求外部服务。');
  const url = new URL(path, 'http://local-preview');
  const query = url.searchParams;
  if (url.pathname.startsWith('/manager/')) {
    if (body !== undefined) {
      const operation = url.pathname === '/manager/backup' ? '备份' : url.pathname.startsWith('/manager/import/') ? '旧统计导入' : '档案变更';
      throw new Error(`本地预览 · ${operation}操作由正式服务执行。`);
    }
    if (query.get('instance') && query.get('instance') !== PREVIEW_INSTANCE_ID) throw new Error('该演示实例不可用。');
    const archive = query.get('archive') || '';
    getPreviewArchive(archive);
    if (url.pathname === '/manager/players') {
      const rows = getPreviewPlayers(archive).filter(player => `${player.name} ${player.id}`.toLowerCase().includes((query.get('q') || '').toLowerCase()) &&
        (query.get('online') !== 'true' || player.isOnline));
      return paginate(sortPlayers(rows, query.get('sort') || 'status', query.get('direction') || 'asc'), query, 50);
    }
    if (url.pathname === '/manager/logins') return paginate(getPreviewLogins(archive, query.get('player')), query, 8);
    if (url.pathname === '/manager/lifetime') return getPreviewLifetime(query.get('player'));
    if (url.pathname === '/manager/long-ticks') return { ...paginate([], query, 20), events: 0 };
    if (url.pathname === '/manager/performance') return [];
    throw new Error('此预览仅演示页面，不连接游戏服务。');
  }
  return previewApi(path, body, options);
}

export function useResource(path) {
  const [state, setState] = useState({ path: null, data: null, error: '', loading: Boolean(path) });
  useEffect(() => {
    let active = true;
    if (path) request(path).then(data => active && setState({ path, data, error: '', loading: false }))
      .catch(error => active && setState({ path, data: null, error: error.message, loading: false }));
    return () => { active = false; };
  }, [path]);
  return state.path === path ? state : { data: null, error: '', loading: Boolean(path) };
}
