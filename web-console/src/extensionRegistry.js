import {usePlatform} from './Platform';
import { WENZHOU_ID, wenzhouRoutes } from './wenzhouRoutes';

const settingsPages = new Map([
  ['memorialtitles', 'title-settings'],
  ['dailyfortunes', 'fortune-settings'],
  ['playerhub', 'hub-settings'],
  [WENZHOU_ID, wenzhouRoutes.at(-1).id],
]);

export function useRegisteredExtensions(preferences) {
  const platform = usePlatform();
  return (platform.value?.extensions || []).map(extension => {
    const isWenzhou = extension.id === WENZHOU_ID;
    const preference = extension.preference || extension.id;
    return {
      ...extension,
      capabilities: extension.capabilities || [],
      preference,
      page: isWenzhou ? wenzhouRoutes[0].id : extension.page || 'settings',
      settingsPage: settingsPages.get(extension.id),
      description: extension.description || (extension.scope === 'global' ? '全局玩家库 · 平台统一存储' : '游戏扩展模块'),
      visible: extension.enabled !== false && preferences[preference] !== false,
      summary: () => extension.id === 'memorialtitles'
        ? `纪念称号 · ${extension.titles ?? 0} 个称号 · ${extension.players ?? 0} 份档案`
        : extension.id === 'dailyfortunes' ? '每日抽签 · 永久签历与收藏' : extension.name,
    };
  });
}
