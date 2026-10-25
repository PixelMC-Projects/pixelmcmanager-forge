import React from 'react';
import { avatarSources } from './playerIdentity';

export function PlayerAvatar({player}) {
  const {source,fallback}=avatarSources(player);
  return <img key={`${source}:${player.avatarVersion??0}`} src={source} alt="" title={player.name+'的头像'} onError={event=>{
    if(event.currentTarget.getAttribute('src')!==fallback)event.currentTarget.src=fallback;
  }}/>;
}
