import {prefix, readValue, writeValue, terraPreviewBillingHold, terraPreviewBillingHoldKey,
  terraPreviewCredentialKey} from '@wenzhou-preview/managerPreview.js';
import {resetPreviewProjectSelection} from '@wenzhou-preview/modelPreview.js';
import {getGrant, players} from './data.js';

const settingsKey = prefix+'terra-settings';
const credentialKey = terraPreviewCredentialKey;
const accountKey = id => prefix+'terra-account:'+id;
const limitFields = ['weeklyLimitNanoUsd','monthlyLimitNanoUsd','serverMonthlyLimitNanoUsd'];
const defaultSettings = {
  effort:'high',fast:false,revision:0,quotaEnabled:true,serverQuotaEnabled:true,
  weeklyLimitNanoUsd:2_000_000_000,monthlyLimitNanoUsd:8_000_000_000,serverMonthlyLimitNanoUsd:160_000_000_000,
};
const defaultGrant = {granted:false,revision:0,mode:'inherit',customWeeklyLimitNanoUsd:null,customMonthlyLimitNanoUsd:null};
const defaultCredential = {configured:false,source:'none',revision:'0',hasManaged:false,hasExternal:false,updatedAt:0};
const getSettings = () => ({...defaultSettings,...readValue(settingsKey)});
const getCredential = () => ({...defaultCredential,...readValue(credentialKey)});
const getPlayer = id => ({...defaultGrant,...readValue(accountKey(id))});
const validAmount = amount => Number.isSafeInteger(amount) && amount >= 0;

function account(grant) {
  const {quotaEnabled,serverQuotaEnabled,weeklyLimitNanoUsd,monthlyLimitNanoUsd,serverMonthlyLimitNanoUsd} = getSettings();
  const personalUnlimited = !quotaEnabled || grant.mode === 'unlimited';
  const enabled = !personalUnlimited || serverQuotaEnabled;
  const weekly = grant.mode === 'custom' ? grant.customWeeklyLimitNanoUsd ?? weeklyLimitNanoUsd : weeklyLimitNanoUsd;
  const monthly = grant.mode === 'custom' ? grant.customMonthlyLimitNanoUsd ?? monthlyLimitNanoUsd : monthlyLimitNanoUsd;
  const exhausted = (!personalUnlimited && (weekly === 0 || monthly === 0)) || (serverQuotaEnabled && serverMonthlyLimitNanoUsd === 0);
  return {
    ...grant,enabled,quotaEnabled,serverQuotaEnabled,personalUnlimited,unlimited:!enabled,
    remainingPercent:exhausted ? 0 : 100,exhausted,resetAt:0,
    defaultWeeklyLimitNanoUsd:weeklyLimitNanoUsd,defaultMonthlyLimitNanoUsd:monthlyLimitNanoUsd,
    weeklyLimitNanoUsd:weekly,weeklyRemainingNanoUsd:weekly,
    monthlyLimitNanoUsd:monthly,monthlyRemainingNanoUsd:monthly,
    serverMonthlyLimitNanoUsd,serverMonthlyRemainingNanoUsd:serverMonthlyLimitNanoUsd,
  };
}

function failure(message, status) {
  return Object.assign(new Error(message), {status});
}

function settingsSnapshot() {
  const hold = terraPreviewBillingHold();
  return {...getSettings(),credential:getCredential(),billingBlocked:Boolean(hold), ...(hold && {billingHoldId:hold.id})};
}

export function holdPreviewTerraBilling() {
  const credential = getCredential();
  if (credential.configured && !terraPreviewBillingHold()) {
    writeValue(terraPreviewBillingHoldKey,{revision:credential.revision,id:crypto.randomUUID()});
    resetPreviewProjectSelection();
  }
}

export function terraPreviewRevision(playerId) {
  const {revision} = getSettings();
  return revision + '/' + (playerId ? getPlayer(playerId).revision
    : getCredential().revision + '/' + (terraPreviewBillingHold()?.id ?? ''));
}

export async function terraPreviewRequest(operation, input, signal) {
  signal.throwIfAborted();
  if (operation === 'terra.settings') return settingsSnapshot();
  if (operation === 'terra.resume') return navigator.locks.request(terraPreviewBillingHoldKey, {signal}, () => {
    const hold = terraPreviewBillingHold();
    if (!hold || hold.revision !== input.revision || hold.id !== input.holdId) {
      throw failure('OpenAI 项目状态已变化，请刷新后重试。',409);
    }
    writeValue(terraPreviewBillingHoldKey,null);
    return settingsSnapshot();
  });
  if (operation === 'terra.configure') return navigator.locks.request(settingsKey, {signal}, () => {
    const previous = getSettings();
    const {effort,fast,revision,quotaEnabled,serverQuotaEnabled} = input;
    if (revision !== previous.revision) throw failure('Terra 配置已修改。',409);
    if (!['high','xhigh','max'].includes(effort) || typeof fast !== 'boolean') {
      throw failure('Terra 配置无效。',400);
    }
    const next = {...previous,effort,fast,revision:revision+1};
    if (limitFields.some(field=>field in input)) {
      if (limitFields.some(field=>!validAmount(input[field])) || typeof quotaEnabled !== 'boolean' || typeof serverQuotaEnabled !== 'boolean') {
        throw failure('Terra 限额无效。',400);
      }
      for (const field of limitFields) next[field] = input[field];
      next.quotaEnabled = quotaEnabled;
      next.serverQuotaEnabled = serverQuotaEnabled;
    }
    writeValue(settingsKey,next);
    return settingsSnapshot();
  });

  if (operation === 'terra.credential') return navigator.locks.request(credentialKey, {signal}, () => {
    const previous = getCredential();
    if (input.revision !== previous.revision) throw failure('OpenAI 密钥状态已修改。',409);
    const hasManaged = input.key !== null;
    if (hasManaged && (typeof input.key !== 'string' || !input.key.trim())) throw failure('请输入测试密钥。',400);
    const {hasExternal} = previous;
    const next = {
      configured:hasManaged || hasExternal,
      source:hasManaged ? 'manager' : hasExternal ? 'environment' : 'none',
      hasManaged,hasExternal,revision:crypto.randomUUID(),updatedAt:hasManaged ? Date.now() : 0,
    };
    writeValue(credentialKey,next);
    return next;
  });

  const {playerId,allowed,revision,mode,customWeeklyLimitNanoUsd,customMonthlyLimitNanoUsd} = input;
  const player = players.find(item=>item.playerId===playerId);
  if (!player) throw failure('未找到该玩家。',404);
  const key = accountKey(playerId);
  if (operation === 'terra.account') return account(getPlayer(playerId));
  if (operation !== 'terra.grant' && operation !== 'terra.quota') throw failure('未知 Terra 操作。',404);

  return navigator.locks.request(key, {signal}, () => {
    const previous = getPlayer(playerId);
    if (revision !== previous.revision) throw failure('Terra 玩家设置已修改。',409);
    const next = {...previous,revision:revision+1};
    if (operation === 'terra.grant') {
      if (!getGrant(player) && allowed) throw failure('需先允许访问问舟。',403);
      if (typeof allowed !== 'boolean') throw failure('Terra 权限无效。',400);
      next.granted = allowed;
    } else {
      if (!['inherit','custom','unlimited'].includes(mode)) throw failure('Terra 限额模式无效。',400);
      next.mode = mode;
      if (mode === 'custom') {
        if (!validAmount(customWeeklyLimitNanoUsd) || !validAmount(customMonthlyLimitNanoUsd)) throw failure('Terra 个人限额无效。',400);
        next.customWeeklyLimitNanoUsd = customWeeklyLimitNanoUsd;
        next.customMonthlyLimitNanoUsd = customMonthlyLimitNanoUsd;
      }
    }
    writeValue(key,next);
    return account(next);
  });
}
