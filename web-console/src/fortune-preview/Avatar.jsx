import React from 'react';
import {defaultHead} from '../playerIdentity';
export {defaultHead} from '../playerIdentity';
export function PlayerAvatar({player,id,name,large=false}){
  const p=player||{id,name};
  return <img className={'player-avatar'+(large?' large-avatar':'')} src={'/default-heads/'+(p.head??defaultHead(p.id||''))+'.png'} alt="" title={p.name+'的示例头像'} width={large?40:32} height={large?40:32}/>;
}
