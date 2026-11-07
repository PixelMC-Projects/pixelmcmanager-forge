// Shared asynchronous community UI state. No fixture or browser-storage authority.
import { useEffect, useRef, useState } from 'react';

export function useCommunityRevision(api, revision) {
  const [local, setLocal] = useState(0);
  useEffect(() => api.subscribe?.(() => setLocal(value => value + 1)), [api]);
  return `${revision ?? ''}:${local}`;
}

export function useCommunityResource(key, read, revision) {
  const latest = useRef(read); latest.current = read;
  const [state, setState] = useState({ key: '', data: null, loading: true, error: null });
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    if (!key) { setState({ key, data: null, loading: false, error: null }); return; }
    let active = true; const controller = new AbortController();
    setState(previous => ({ key, data: previous.key === key ? previous.data : null, loading: true, error: null }));
    Promise.resolve().then(() => latest.current(controller.signal)).then(data => {
      if (active) setState({ key, data, loading: false, error: null });
    }, error => {
      if (!active || error.name === 'AbortError') return;
      setState(previous => ({ key, data: [401, 403, 404].includes(error.status) ? null : previous.key === key ? previous.data : null, loading: false, error }));
    });
    return () => { active = false; controller.abort(); };
  }, [key, revision, retry]);
  return { ...(state.key === key ? state : { data: null, loading: true, error: null }), reload: () => setRetry(value => value + 1) };
}

export function useCommunityCatalogue(key, readPage, revision) {
  const [wanted, setWanted] = useState(0);
  useEffect(() => setWanted(0), [key]);
  const resource = useCommunityResource(key, async signal => {
    const pages = await Promise.all(Array.from({ length: wanted + 1 }, (_, page) => readPage(page, signal)));
    const baseline = pages[0];
    if (pages.some(part => part.revision && baseline.revision && part.revision !== baseline.revision)) throw new Error('档案已更新，请重新读取后继续。');
    return { ...baseline, rows: pages.flatMap(part => part.rows), hasMore: pages.at(-1).hasMore };
  }, `${revision}:${wanted}`);
  return { ...resource, loadMore: () => setWanted(value => value + 1) };
}
