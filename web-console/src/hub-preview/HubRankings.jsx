// SPDX-License-Identifier: Apache-2.0
// Preview-only adapter. Production builds must never import this module or demo fixture.
import React, { useRef, useState, useSyncExternalStore } from 'react';
import { BOARD_DEFINITIONS } from '@hub-leaderboards-contract';
import { getDemoProjection, getDemoState, setDemoState, subscribeDemo } from '@hub-leaderboards-demo';
import { HubRankings } from '../playerhub/HubRankings.jsx';
import { defaultHead } from '../playerIdentity.js';

function Avatar({ row }) {
  const fallback = import.meta.env.BASE_URL+'default-heads/'+defaultHead(row.playerId)+'.png';
  return <img src={row.avatar?.url || fallback} alt="" title={row.name+'的示例头像'} onError={event => { if (event.currentTarget.getAttribute('src') !== fallback) event.currentTarget.src = fallback; }}/>;
}
const statuses = [['ready','正常'],['loading','读取中'],['empty','空榜'],['unavailable','暂不可用'],['unsupported','不支持'],['error','读取失败']];
export function HubRankingsPreview({ preferences }) {
  const controls = useSyncExternalStore(subscribeDemo, getDemoState), cache = useRef(new Map());
  const [board,setBoard] = useState('food'), [page,setPage] = useState(0);
  const projection = getDemoProjection(board, { page, size:20 }), key = `${board}:${page}:${controls.selfAbsent}`;
  const confirmed = projection.status === 'ready' || projection.status === 'empty';
  if (confirmed) { cache.current.set(key, projection); while (cache.current.size > 12) cache.current.delete(cache.current.keys().next().value); }
  const data = confirmed ? projection : projection.status === 'unsupported' ? null : cache.current.get(key) || null;
  const counts = Object.fromEntries(BOARD_DEFINITIONS.map(item => [item.id, getDemoProjection(item.id).total]));
  const previewControls = <details className="ph-ranking-demo"><summary>预览状态</summary><div>{statuses.map(([id,label]) => <button key={id} type="button" aria-pressed={controls.status === id} onClick={() => setDemoState({ status:id })}>{label}</button>)}</div></details>;
  return <HubRankings preferences={preferences} board={board} onBoard={setBoard} page={page} onPage={setPage} resource={{data,status:projection.status}} counts={counts} reload={() => setDemoState({status:'ready'})} Avatar={Avatar} previewControls={previewControls}/>;
}
