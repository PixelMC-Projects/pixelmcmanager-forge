import React, { useEffect, useState } from 'react';
import { effortKey, readValue, writeValue } from '@wenzhou-preview/managerPreview.js';
import { credentialKey, formatTime } from './data.js';
import ModelSettings from '@wenzhou-admin/wenzhou/ModelSettings.jsx';
import {holdPreviewTerraBilling, terraPreviewRequest, terraPreviewRevision} from './terraPreview.js';
import {previewQuotaSettings, resetAllPreviewQuotas, savePreviewQuotaSettings} from '@wenzhou-preview/quotaPreview.js';
import AccountPreview from './AccountPreview.jsx';

export default function Service({toast,setUnsaved}) {
  const {quotaEnabled,weeklyQuotaNanoYuan,resetRevision=0}=previewQuotaSettings();
  const credential=readValue(credentialKey);
  const [modelDirty,setModelDirty]=useState(false);

  useEffect(()=>{
    setUnsaved(modelDirty);
    if (!modelDirty) return;
    function leaving(event) {event.preventDefault();event.returnValue='';}
    window.addEventListener('beforeunload',leaving);
    return ()=>{window.removeEventListener('beforeunload',leaving);setUnsaved(false);};
  },[modelDirty,setUnsaved]);

  return <div className="settings-scroll"><div className="settings-content">
    <AccountPreview toast={toast}/>
    <ModelSettings credential={credential} updatedAt={formatTime(credential?.updatedAt)}
      enabled={quotaEnabled} limit={weeklyQuotaNanoYuan} resetRevision={resetRevision} onDirty={setModelDirty} toast={toast}
      terraApi={terraPreviewRequest} terraRevision={terraPreviewRevision()} onPreviewBillingHold={holdPreviewTerraBilling}
      preview online effort={readValue(effortKey,'low')} onEffort={value=>writeValue(effortKey,value)}
      onCredential={key=>writeValue(credentialKey,{configured:key!==null,updatedAt:Date.now()})}
      onQuota={savePreviewQuotaSettings} onReset={resetAllPreviewQuotas}/>
  </div></div>;
}
