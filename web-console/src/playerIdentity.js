// Same UUID-based default selection as MemorialTitles, without requiring that module.
export function defaultHead(id) {
  try {
    const value=BigInt('0x'+id.replaceAll('-',''));
    const hash=Number(BigInt.asIntN(32,value^(value>>32n)^(value>>64n)^(value>>96n)));
    return ((hash%18)+18)%18;
  } catch { return 6; }
}

export function avatarSources(player) {
  const fallback=`/default-heads/${defaultHead(player.id)}.png`;
  const source=player.avatarUrl || `/api/manager/avatars/${player.id}.png?v=${player.avatarVersion??0}`;
  return {source,fallback};
}
