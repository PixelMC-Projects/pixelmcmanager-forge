const names=new Intl.Collator('zh-CN-u-co-pinyin',{sensitivity:'base',numeric:true});
const vanilla=['minecraft:overworld','minecraft:the_nether','minecraft:the_end'];
const ids={'主世界':vanilla[0],'下界':vanilla[1],'末地':vanilla[2]};
export function dimensionId(player){return player.worldCode||ids[player.world]||player.world||'';}
export function dimensionCompare(a,b){const first=dimensionId(a),second=dimensionId(b),i=vanilla.indexOf(first),j=vanilla.indexOf(second);return (i<0?3:i)-(j<0?3:j)||names.compare(first,second);}
export function sessionDuration(player){if(player.isOnline===false)return null;if(Number.isFinite(player.sessionOnlineMillis))return player.sessionOnlineMillis;const text=player.online||'',units=[['天',86400000],['小时',3600000],['分钟',60000],['秒',1000]];let found=false,total=0;for(const [unit,multiplier] of units){const match=text.match(new RegExp('(\\d+)'+unit));if(match){found=true;total+=Number(match[1])*multiplier;}}return found?total:null;}
export function sortPlayers(players,key='name',direction='asc') {
  const sign=direction==='desc'?-1:1;
  return [...players].sort((a,b)=>{
    let primary=0;
    if(key==='status') {
      const knownA=typeof a.isOnline==='boolean',knownB=typeof b.isOnline==='boolean';
      if(!knownA||!knownB)return knownA?-1:knownB?1:names.compare(a.name,b.name)||String(a.id).localeCompare(String(b.id));
      primary=Number(b.isOnline)-Number(a.isOnline);
    }
    else if(key==='name')primary=names.compare(a.name,b.name);
    else if(key==='world'){const first=dimensionId(a),second=dimensionId(b);if(!first||!second)return first?-1:second?1:names.compare(a.name,b.name);primary=dimensionCompare(a,b);}
    else {const value=player=>key==='ping'?(player.fake||player.isOnline===false?null:player.ping):key==='session'?sessionDuration(player):player[key];const first=value(a),second=value(b),knownA=Number.isFinite(first),knownB=Number.isFinite(second);if(!knownA||!knownB)return knownA?-1:knownB?1:names.compare(a.name,b.name);primary=first-second;}
    return primary*sign||names.compare(a.name,b.name)||String(a.id).localeCompare(String(b.id));
  });
}
