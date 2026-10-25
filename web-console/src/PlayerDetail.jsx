import React from 'react';
import {useResource} from './transport';
import { Robot } from '@phosphor-icons/react';
import { formatPlayTime } from './telemetry';
import { latencyTone } from './trendData';
import { PlayerLoginHistory } from './PlayerLoginHistory';
import { PlayerAvatar } from './PlayerAvatar';

export function PlayerDetail({player:initial,telemetry:t}) {
  const remote=useResource(initial.fake?null:t.platform.scope('/manager/players?q='+initial.id),2000);
  const lifetime=useResource(initial.fake?null:'/manager/lifetime?player='+initial.id);
  const player=initial.fake?t.players.find(p=>p.id===initial.id)||initial:remote.data?.rows?.find(p=>p.id===initial.id)||initial;
  return <>
    <section className="player-profile-card" aria-label="玩家信息">
      <div className="drawer-player">{player.fake?<span className="robot-avatar"><Robot size={24}/></span>:<PlayerAvatar player={player}/>}<span className="drawer-player-identity"><strong>{player.name}</strong><small className="mono" title={player.id}>{player.id}</small></span></div>
      <div className="player-profile-totals"><div><span>总在线时长</span><strong>{formatPlayTime(player.totalOnlineMillis)}</strong></div><div><span>总登录次数</span><strong>{player.joinCount??'—'}</strong></div></div>
      <dl className="player-profile-details"><div><dt>在线状态</dt><dd><span className={'player-presence '+(t.stale&&!t.stopped&&!t.historical?'unknown':player.isOnline===false?'offline':'online')} role="img" aria-label={t.stale&&!t.stopped&&!t.historical?'状态未更新':player.isOnline===false?'离线':'在线'}/><span className="presence-caption">{t.stale&&!t.stopped&&!t.historical?'未更新':player.isOnline===false?'离线':'在线'}</span></dd></div><div><dt>所在维度</dt><dd>{t.stale||player.isOnline===false?'—':player.world}</dd></div><div><dt>延迟</dt><dd className={'metric-tone-'+latencyTone(player.ping,player.fake||player.isOnline===false||t.stale)}>{player.fake||player.isOnline===false||t.stale?'—':player.ping+' ms'}</dd></div><div><dt>本次在线</dt><dd>{t.stale||player.isOnline===false?'—':formatPlayTime(player.sessionMillis)}</dd></div></dl>
    </section>
    <p className="quiet-note">跨整合包累计：{formatPlayTime(lifetime.data?.totalOnlineMillis)} · {lifetime.data?.joinCount??'—'} 次登录</p><PlayerLoginHistory key={player.id} player={player} telemetry={t}/>
  </>;
}
