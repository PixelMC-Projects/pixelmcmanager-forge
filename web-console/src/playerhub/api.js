import { useEffect, useState } from 'react';
import { request } from '../transport.js';

export async function api(path, body, options) {
  try { return await request('/playerhub' + path, body, options); }
  catch (error) { error.playerHubPath = path; throw error; }
}
export function query(path, values) {
  return path + '?' + new URLSearchParams(Object.entries(values).filter(([, v]) => v !== undefined && v !== null));
}
export function failure(error) {
  if ((error?.status === 404 || error?.status === 405) && /^\/(state|entries)(\?|$)/.test(error.playerHubPath || '')) return '服务器尚未提供 PlayerHub 更新日志管理接口，请升级 Manager 服务。';
  if (error?.status === 409) return error.message + ' 请重新读取最新数据后再提交；当前草稿仍然保留。';
  return error?.message || 'PlayerHub 数据暂不可用。';
}
// Revision changes come from Manager's existing SSE stream or a completed mutation.
// A refresh retains the last page until the replacement response succeeds.
export function useHubResource(path, revision = '') {
  const [state, setState] = useState({ data: null, loading: !!path, error: null });
  const [nonce, setNonce] = useState(0);
  useEffect(() => {
    if (!path) { setState(value => ({ ...value, loading: false, error: null })); return; }
    let active = true;
    const abort = new AbortController();
    setState(value => ({ ...value, loading: true, error: null }));
    api(path, undefined, { signal: abort.signal }).then(data => {
      if (active) setState({ data, loading: false, error: null });
    }, error => {
      if (active && error.name !== 'AbortError') setState(value => ({ ...value, loading: false, error }));
    });
    return () => { active = false; abort.abort(); };
  }, [path, revision, nonce]);
  return { ...state, reload: () => setNonce(value => value + 1) };
}

// Packs are appended only after an explicit request. Already opened pages refresh
// with the same SSE revision as the rest of the visible workspace.
export function useHubPacks(snapshot, revision) {
  const [wanted, setWanted] = useState(0), [extra, setExtra] = useState([]);
  const [status, setStatus] = useState({ loading: false, error: null, hasMore: null });
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    if (!wanted) return;
    let active = true; const abort = new AbortController();
    setStatus(value => ({ ...value, loading: true, error: null }));
    Promise.all(Array.from({ length: wanted }, (_, index) => api(query('/packs', { page: index + 1 }), undefined, { signal: abort.signal }))).then(pages => {
      if (!active) return;
      setExtra(pages.flatMap(page => page.rows));
      setStatus({ loading: false, error: null, hasMore: pages.at(-1).hasMore });
    }, error => { if (active && error.name !== 'AbortError') setStatus(value => ({ ...value, loading: false, error })); });
    return () => { active = false; abort.abort(); };
  }, [wanted, revision, retry]);
  const packs = [...(snapshot?.packs || []), ...extra].filter((pack, i, all) => all.findIndex(value => value.id === pack.id) === i);
  return { packs, loading: status.loading, error: status.error, hasMore: status.hasMore ?? !!snapshot?.packsHasMore, load: () => setWanted(value => value + 1), retry: () => setRetry(value => value + 1) };
}
