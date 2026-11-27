// Explicit local preview wrapper; never imported by the authenticated entry.
import React from 'react';
import { HubHistoryArchives as Shared } from '../playerhub/HubHistoryArchives.jsx';
import { communityPreviewApi } from './communityPreviewApi.js';
export function HubHistoryArchives(props) { return <Shared {...props} api={communityPreviewApi}/>; }
export default HubHistoryArchives;
