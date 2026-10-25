const mainDimensions=['minecraft:overworld','minecraft:the_nether','minecraft:the_end'];
export const overviewWorlds=worlds=>mainDimensions.map(code=>worlds.find(world=>world.code===code)).filter(Boolean);
