import { COMMUNITY_PLAYERS } from '@hub-community/demo.js';
import { archivePrefix, grantKey, historyKey, prefix, readValue, writeValue } from '@wenzhou-preview/managerPreview.js';
import { demoReceipt } from '@wenzhou-preview/usage.js';
import { previewSessionTitle, restorePreviewReply } from '@wenzhou-preview/guide.js';

export const players = COMMUNITY_PLAYERS;
const sampleEnd = Date.parse('2026-11-18T16:30:00+08:00');
const samples = [
  [['签筒怎么摆在桌上？','拿着签筒，蹲下对准桌面按右键。'],['摆好后怎么抽签？','松开潜行，再对着签筒按右键。']],
  [['火把怎么合成？','把煤炭或木炭放在木棍上方，一次能得到四支火把。']],
  [['怎么把喜欢的签展示在手册里？','先把签归档，再到我的签笺中选中它并设为展示。']],
  [['工作台需要几块木板？','把四块木板摆成两行两列。'],['不同木板能混用吗？','原版工作台配方允许混用属于木板标签的材料。']],
  [['怎么回看以前整合包的纪念？','从个人主页打开旅程档案，再选择想看的整合包。']],
  [['在哪里改玩家名片？','从主页打开自己的公开资料，点击编辑资料。']],
];
const fixtures = players.flatMap((player,index) => {
  if (player.isSelf || index % 5 === 0) return [];
  return Array.from({length:1 + index % 4}, (_, n) => {
    const createdAt = sampleEnd - (index * 3 + n * 11) * 3600000;
    const turns = samples[(index+n)%samples.length].map(([question,answer],i) => ({
      id:'turn-'+i, createdAt:createdAt+i*60000, question, reply:{text:answer}, events:[],
      usageReceipts: [i ? 'followup' : 'lookup', 'answer'].map(stage =>
        demoReceipt([index, n, i, stage].join('/'), stage, createdAt + i * 60000))
    }));
    return {id:'example-'+index+'-'+n,playerId:player.playerId,updated:turns.at(-1).createdAt,turns};
  });
});
export const credentialKey = prefix+'credential-state';
export const formatTime = ms => Number.isFinite(ms) ? new Intl.DateTimeFormat('sv-SE',{
  timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit'
}).format(ms) : '—';

export function getGrant(player) {
  const saved = readValue(grantKey(player.playerId));
  return typeof saved === 'boolean' ? saved : !player.isSelf && players.indexOf(player)%5 !== 0 && players.indexOf(player)%7 !== 0;
}

export function listSessions() {
  const selfId=players.find(p=>p.isSelf).playerId;
  const local=new Map();
  const history=readValue(historyKey,[]);
  if (Array.isArray(history)) for (const row of history) local.set(row.id,row);
  for (const key of Object.keys(localStorage)) {
    if (!key.startsWith(archivePrefix)) continue;
    const row=readValue(key);
    if (row?.id) local.set(row.id,row);
  }
  return [...fixtures,...Array.from(local.values(),row=>({...row,playerId:selfId}))]
    .filter(row=>Array.isArray(row.turns)&&row.turns.length)
    .map(row=>({...row,title:previewSessionTitle(row),
      count:row.turns.filter(turn=>turn.reply || turn.stopped || turn.failure || turn.topicPrompt).length,player:players.find(p=>p.playerId===row.playerId)}));
}

export function logRead(row) {
  writeValue(prefix+'audit:'+crypto.randomUUID(), {
    operator:'zcpu', sessionId:row.id, playerId:row.playerId, openedAt:Date.now(),
  });
}

function itemText(parts,items) {
  if (typeof parts==='string') return parts;
  return (parts||[]).map(p=>typeof p==='string'?p:(items?.[p.item]?.name||p.item||'')+(p.explanation?'（'+p.explanation+'）':'')).join('');
}
export function answerLines(turn) {
  const reply=restorePreviewReply(turn.reply);
  if (!reply) {
    const status = turn.stopped ? '已停止回答' : turn.failure;
    return status || turn.topicPrompt ? [status, turn.topicPrompt].filter(Boolean) : ['本次提问尚未收到回答。'];
  }
  const lines=[itemText(reply.text,reply.items)];
  const recipeList = Array.isArray(reply.recipes) && reply.recipes.length ? reply.recipes : (reply.recipe ? [reply.recipe] : []);
  for (const recipe of recipeList) {
    if (Array.isArray(recipe?.slots) && recipe.columns > 0) {
      const slots=recipe.slots.map(id=>id?reply.items?.[id]?.name||id:'空');
      for (let i=0;i<slots.length;i+=recipe.columns) lines.push(slots.slice(i,i+recipe.columns).join(' · '));
      lines.push('产物：'+(reply.items?.[recipe.output]?.name||recipe.output)+' × '+recipe.count);
    }
  }
  if (reply.note) lines.push(itemText(reply.note,reply.items));
  return lines.filter(Boolean);
}
