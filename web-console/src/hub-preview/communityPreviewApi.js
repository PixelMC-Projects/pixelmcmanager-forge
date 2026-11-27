// Explicit local-only adapter. Production entry must never import this file.
import * as preview from '@hub-community/adminPreview.js';
import { compareValue } from '../titles/sorting.js';

const page = (rows, index, size) => ({ rows: rows.slice(index * size, (index + 1) * size), total: rows.length, page: index, size, hasMore: (index + 1) * size < rows.length });
function profile(playerId) { const record = preview.readAdminProfile(playerId), options = preview.getProfileOptions(playerId); return { profile: { ...record, featuredMemory: options.memories.find(item => item.id === record.featuredMemoryId) || null }, options }; }
function memory(playerId, archiveId, index = 0) {
  const record = preview.readAdminMemory(playerId, archiveId), candidates = preview.getMemoryCollectionCandidates(playerId, archiveId);
  const selection = record.collections.selection;
  return { ...record, supplements: record.supplements.slice(index * 20, (index + 1) * 20), supplementsTotal: record.supplements.length, supplementsPage: index, supplementsHasMore: (index + 1) * 20 < record.supplements.length,
    collections: { ...record.collections, items: candidates.filter(item => Object.values(selection).includes(item.eventId)) }, candidates: candidates.slice(0, 100), candidatesTotal: candidates.length, candidatesHasMore: candidates.length > 100 };
}
async function mutate(method, body) { await preview[method](body); return memory(body.playerId, body.archiveId); }
export const communityPreviewApi = Object.freeze({
  subscribe: preview.subscribeCommunity,
  profile,
  profileOptions(playerId, kind, index) { return { ...page(preview.getProfileOptions(playerId)[kind], index, 100), playerId, kind }; },
  async saveProfile(body) { await preview.saveAdminProfile(body); return profile(body.playerId); },
  archives(instance, index) { return page(preview.getCommunityArchives(), index, 100); },
  players({ archiveId, search = '', presence = 'all', sort = 'status', direction = 'asc', page: index = 0, size = 20 }) {
    const rows = (archiveId ? preview.getArchivePlayers(archiveId) : preview.getCommunityPlayers()).map(player => ({ ...player, profile: profile(player.playerId).profile }));
    const presenceCounts = { all: rows.length, online: rows.filter(row => row.isOnline).length, offline: rows.filter(row => !row.isOnline).length };
    const key = row => ({ name: row.name, bio: row.profile.bio, memory: row.profile.featuredMemory?.name, collection: [row.profile.featuredTitle?.name, row.profile.featuredFortune?.title].filter(Boolean).join(' / '), lastLogin: Date.parse(row.lastLoginAt) || null })[sort];
    const filtered = rows.filter(row => (presence === 'all' || row.isOnline === (presence === 'online')) && `${row.name} ${row.playerId}`.toLowerCase().includes(search.toLowerCase()));
    filtered.sort((a, b) => (sort === 'status' ? (direction === 'asc' ? -1 : 1) * (Number(a.isOnline) - Number(b.isOnline)) : compareValue(key(a), key(b), direction)) || compareValue(a.name, b.name) || a.playerId.localeCompare(b.playerId));
    const appearance = rows.length ? preview.getProfileOptions(rows[0].playerId) : {};
    return { ...page(filtered, index, size), presenceCounts, backgrounds: appearance.backgrounds || [], patterns: appearance.patterns || [] };
  },
  memory,
  memoryOptions(playerId, archiveId, index) { return { ...page(preview.getMemoryCollectionCandidates(playerId, archiveId), index, 100), playerId, archiveId }; },
  saveNote: body => mutate('adminSaveMemoryNote', body),
  deleteNote: body => mutate('adminDeleteMemoryNote', body),
  saveSupplement: body => mutate('adminSaveMemorySupplement', body),
  deleteSupplement: body => mutate('adminDeleteMemorySupplement', body),
  saveCollections: body => mutate('adminSaveMemoryCollections', body),
});
