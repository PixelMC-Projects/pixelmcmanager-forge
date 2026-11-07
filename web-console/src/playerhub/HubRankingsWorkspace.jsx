// SPDX-License-Identifier: Apache-2.0
import React, { useEffect, useRef, useState } from 'react';
import { validateProjection } from '@hub-leaderboards-contract';
import { usePlatform } from '../Platform.jsx';
import { PlayerAvatar } from '../PlayerAvatar.jsx';
import { api, query } from './api.js';
import { HubRankings } from './HubRankings.jsx';

function Avatar({ row }) { return <PlayerAvatar player={{ id:row.playerId, name:row.name, avatarUrl:row.avatar?.url, avatarVersion:row.avatar?.version }}/>; }
export function HubRankingsWorkspace({ preferences }) {
  const platform = usePlatform(), instance = platform?.instance || '';
  const [board,setBoard] = useState('food'), [page,setPage] = useState(0), [revision,setRevision] = useState(0);
  const publishedRevision = platform?.value?.leaderboardsRevisions?.[board] || '';
  const cache = useRef(new Map()), [counts,setCounts] = useState({});
  const key = `${instance}:${board}:${page}`, [state,setState] = useState({ key:'', data:null, status:'loading' });
  useEffect(() => { cache.current.clear(); setCounts({}); setPage(0); }, [instance]);
  useEffect(() => {
    let active = true; const controller = new AbortController(), prior = cache.current.get(key) || null;
    const forgetBoard = () => {
      for (const saved of cache.current.keys()) if (saved.startsWith(`${instance}:${board}:`)) cache.current.delete(saved);
      setCounts(value => ({ ...value, [board]:null }));
    };
    setState({ key, data:prior, status:prior ? 'ready' : 'loading' });
    api(query('/leaderboards', { instance, board, period:'all', page, size:20 }), undefined, { signal:controller.signal }).then(result => {
      try { validateProjection(result); } catch { throw new Error('排行数据格式无效，请检查服务版本后重试。'); }
      if (result.board !== board || result.page !== page || result.size !== 20) throw new Error('排行响应与当前页面不一致。');
      if (!active) return;
      const confirmed = result.status === 'ready' || result.status === 'empty';
      if (confirmed && page > Math.max(0, Math.ceil(result.total / result.size) - 1)) {
        setPage(Math.max(0, Math.ceil(result.total / result.size) - 1));
        return;
      }
      if (confirmed) {
        cache.current.set(key, result);
        while (cache.current.size > 12) cache.current.delete(cache.current.keys().next().value);
        setCounts(value => ({ ...value, [board]:result.total }));
      }
      if (result.status === 'unsupported') forgetBoard();
      setState({ key, data:confirmed ? result : result.status === 'unsupported' ? null : prior, status:result.status });
    }).catch(error => {
      if (!active || error.name === 'AbortError') return;
      if ([401,403].includes(error.status)) {
        cache.current.clear(); setCounts({});
        setState({ key, data:null, status:'error', error:'无权查看此排行，请确认账号权限。' });
        return;
      }
      const unsupported = [404,405,501].includes(error.status);
      if (unsupported) forgetBoard();
      setState({ key, data:unsupported ? null : prior, status:unsupported ? 'unsupported' : 'error', error:unsupported ? '' : error instanceof TypeError ? '无法连接排行服务，请检查网络后重试。' : error.message || '排行读取失败。' });
    });
    return () => { active = false; controller.abort(); };
  }, [instance,board,page,revision,publishedRevision,key]);
  const resource = state.key === key ? state : { data:cache.current.get(key) || null, status:'loading' };
  return <HubRankings preferences={preferences} board={board} onBoard={setBoard} page={page} onPage={setPage} resource={resource} counts={counts} reload={() => setRevision(value => value+1)} Avatar={Avatar}/>;
}
