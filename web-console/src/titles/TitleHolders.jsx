import React, { useState } from 'react';
import { ArrowClockwise, Users } from '@phosphor-icons/react';
import { api, qs, time } from './api';
import { PlayerAvatar } from './PlayerAvatar';
import { Empty, ErrorNotice, Pagination, useRemote } from './ui';

export function TitleHolders({ title }) {
  const [page, setPage] = useState(0);
  const result = useRemote(() => api('/title-holders'+qs({ title: title.id, page, size: 20 })), [title.id, page]);
  const data = result.data;
  return <section className="title-holders" aria-label="称号持有名单">
    <div className="holders-heading"><span>{data && !result.loading ? <><strong>{data.total}</strong> 位持有者</> : '持有者'}</span><button className="button quiet" disabled={result.loading} onClick={result.reload}><ArrowClockwise size={14}/>刷新</button></div>
    <ErrorNotice error={result.error} retry={result.reload}/>
    {result.loading ? <p className="loading-copy" role="status">正在读取持有名单…</p> : !result.error && (data?.rows.length ? <>
      <table className="holders-table"><caption className="visually-hidden">按玩家名称首字母排序的全部持有者</caption><thead><tr><th scope="col">游戏内 ID</th><th scope="col">UUID</th><th scope="col">获得时间</th></tr></thead><tbody>{data.rows.map(player => <tr key={player.id}>
        <td><div className="holder-identity"><PlayerAvatar id={player.id} version={player.avatarVersion} name={player.name}/><span title={player.name}>{player.name}</span></div></td>
        <td className="holder-uuid mono"><span className="holder-mobile-label">UUID</span>{player.id}</td>
        <td className="holder-acquired"><span className="holder-mobile-label">获得时间</span><time dateTime={new Date(player.acquiredAt).toISOString()}>{time(player.acquiredAt)}</time></td>
      </tr>)}</tbody></table>
      <Pagination page={data.page} size={data.size} total={data.total} onPage={setPage}/>
    </> : <Empty icon={Users} title="暂无持有者">目前还没有玩家持有这个称号。</Empty>)}
  </section>;
}
