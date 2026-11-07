// PixelMC-Hub Copyright 2026 zhan_cpu / PixelMC Projects. Apache-2.0.
// Presentation-only helpers adapted from PixelMCPlayerHub/src/changelog/model.js.
export const statusNames = { published: '已发布', draft: '草稿', withdrawn: '已撤下' };
export const categories = ['功能更新', '体验优化', '问题修复', '整合包更新', '通知'];
export function formatTime(value, short = false) {
  if (value === null || value === undefined || value === '') return '尚未发布';
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return '—';
  return new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', ...(short ? {} : { hour: '2-digit', minute: '2-digit', hour12: false }) }).format(date).replaceAll('/', '.');
}
export function packLabel(pack) { return [pack?.shortName || pack?.name, pack?.version].filter(Boolean).join(' '); }
export function orderedPacks(state) { return [...state.packs].sort((a, b) => Number(b.current) - Number(a.current) || (b.createdAt || 0) - (a.createdAt || 0) || a.id.localeCompare(b.id)); }
export function formatVersion(entry, pack) {
  return (entry.packName || pack?.shortName || pack?.name || '') + (entry.packVersion || '') + (entry.updateVersion ? '-' + entry.updateVersion : '') || '更新记录';
}
export function freshDraft(state) {
  const pack = state.packs.find(pack => pack.current) || state.packs[0];
  return { id: null, title: '', summary: '', packName: pack?.shortName || pack?.name || '', packVersion: pack?.version || '', updateVersion: '', body: '', category: categories[0], packId: pack?.id || '', pinned: false, revision: 0 };
}
