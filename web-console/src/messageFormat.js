// Mirrors TextTemplateParser + LegacyFormatParser + GradientParser, not MiniMessage.
const colors = ['000000','0000aa','00aa00','00aaaa','aa0000','aa00aa','ffaa00','aaaaaa','555555','5555ff','55ff55','55ffff','ff5555','ff55ff','ffff55','ffffff'];
const formats = {k:'obfuscated',l:'bold',m:'strike',n:'underline',o:'italic'};
const globalNames = [['online','在线人数'],['max','人数上限'],['date','当前日期'],['time','当前时间']];
export const messagePlaceholders = scope => (scope === 'global' ? globalNames : [['player','玩家名称'],['uuid','玩家 UUID'],...globalNames,['join_count','登录次数'],['playtime','累计游玩时长'],['playtime_hours','累计小时'],['playtime_minutes','累计分钟'],['ping','玩家延迟'],['first_join_date','首次登录日期'],['last_join_date','上次登录日期']]).map(([name,label])=>({token:`{${name}}`,label}));
export function resolveMessage(input, values = {}) {
  return input.replace(/\{([a-zA-Z0-9_]+)}/g,(token,name)=>Object.hasOwn(values,name)?String(values[name]):token);
}
export function parseMessage(input) {
  const runs=[];let style={},index=0;
  const append=(text,format=style)=>{
    if(!text)return;
    const last=runs.at(-1);
    if(last&&JSON.stringify(last.style)===JSON.stringify(format))last.text+=text;
    else runs.push({text,style:{...format}});
  };
  while(index<input.length) {
    if(input.startsWith('<gradient:',index)) {
      const headerEnd=input.indexOf('>',index),close=input.indexOf('</gradient>',headerEnd+1);
      const pair=headerEnd>=0?input.slice(index+10,headerEnd).split(':').map(color=>color.trim().replace(/^#/,'')):[];
      if(close>=0&&pair.length===2&&pair.every(color=>/^[0-9a-f]{6}$/i.test(color))) {
        const points=Array.from(input.slice(headerEnd+1,close)),start=parseInt(pair[0],16),end=parseInt(pair[1],16);
        points.forEach((point,i)=>{
          const ratio=points.length===1?0:Math.fround(i/(points.length-1));let rgb=0;
          for(const shift of [16,8,0]){const a=(start>>shift)&255,b=(end>>shift)&255;rgb|=Math.round(Math.fround(a+Math.fround((b-a)*ratio)))<<shift;}
          append(point,{...style,color:'#'+rgb.toString(16).padStart(6,'0')});
        });
        index=close+11;continue;
      }
    }
    if(input[index]==='&') {
      const hex=input.slice(index+2,index+8),code=input[index+1]?.toLowerCase();
      if(code==='#'&&/^[0-9a-f]{6}$/i.test(hex)){style={...style,color:'#'+hex.toLowerCase()};index+=8;continue;}
      if(code&&/^[0-9a-f]$/.test(code)){style={...style,color:'#'+colors[parseInt(code,16)]};index+=2;continue;}
      if(code==='r'){style={};index+=2;continue;}
      if(formats[code]){style={...style,[formats[code]]:true};index+=2;continue;}
    }
    const point=String.fromCodePoint(input.codePointAt(index));append(point);index+=point.length;
  }
  return runs;
}

