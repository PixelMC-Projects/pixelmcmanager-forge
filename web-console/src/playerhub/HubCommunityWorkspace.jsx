import React from 'react';
import { usePlatform } from '../Platform.jsx';
import { communityAdminApi } from './communityApi.js';
import { HubPlayerCards } from './HubPlayerCards.jsx';
import { HubHistoryArchives } from './HubHistoryArchives.jsx';

export function HubCommunityWorkspace(props) {
  const platform = usePlatform();
  const capability = platform?.value?.communityApi;
  if (capability !== 1) return <p className="quiet-note" role="status">{platform?.error || !platform?.value ? '正在确认社区服务状态…' : '当前 Manager 服务尚未提供社区管理功能，请升级对应服务。'}</p>;
  const shared = { ...props, api: communityAdminApi, revision: platform.value.communityRevision, instance: platform.instance };
  return props.page === 'hub-players' ? <HubPlayerCards {...shared}/> : <HubHistoryArchives {...shared}/>;
}
