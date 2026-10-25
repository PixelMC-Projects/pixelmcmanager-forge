import React from 'react';
import { defaultHead } from '../playerIdentity';
export { defaultHead } from '../playerIdentity';
export function PlayerAvatar({ id, version = 0, name, large = false }) {
  return <img className={`player-avatar ${large ? 'large-avatar' : ''}`} src={'/api/avatars/'+id+'.png?v='+version} alt="" aria-hidden="true" title={name+'的头像'} width={large ? 36 : 32} height={large ? 36 : 32} onError={event => { event.currentTarget.onerror = null; event.currentTarget.src='/default-heads/'+defaultHead(id)+'.png'; }}/>
}
