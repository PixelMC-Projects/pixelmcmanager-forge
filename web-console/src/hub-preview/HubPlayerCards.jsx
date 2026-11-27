// Explicit local preview wrapper; never imported by the authenticated entry.
import React from 'react';
import { HubPlayerCards as Shared } from '../playerhub/HubPlayerCards.jsx';
import { communityPreviewApi } from './communityPreviewApi.js';
export function HubPlayerCards(props) { return <Shared {...props} api={communityPreviewApi}/>; }
export default HubPlayerCards;
