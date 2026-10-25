import {request} from '../transport';
export {ApiError,setCsrf} from '../transport';
export const api=request;
export async function download(path, filename) {
  const response = await api(path, undefined, { raw: true });
  const url = URL.createObjectURL(await response.blob());
  const a = document.createElement('a'); a.href = url; a.download = filename; a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
export const qs = values => '?' + new URLSearchParams(Object.entries(values).filter(([, value]) => value !== '' && value !== undefined));
export const time = value => value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short', hour12: false }).format(new Date(value)) : '—';
export const textOf = title => title?.name?.segments?.map(s => s.text).join('') || '';
export const creationDate = value => value ? new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date(value)) : '创建日期未记录';
export const pinyin = new Intl.Collator('zh-CN-u-co-pinyin',{sensitivity:'base',numeric:true});
export const hex = color => '#' + (color ?? 0xffffff).toString(16).padStart(6, '0');
export const bytes = value => value < 1024 ? `${value} B` : value < 1024 * 1024 ? `${(value / 1024).toFixed(1)} KB` : `${(value / 1024 / 1024).toFixed(1)} MB`;
export function glyphs(rich) {
  const out = (rich?.segments || []).flatMap(segment => Array.from(segment.text).map(text => ({ ...segment, text })));
  for (const gradient of rich?.gradients || []) gradient.positions.forEach((position, index) => {
    if (!out[position]) return;
    const t = gradient.positions.length <= 1 ? 0 : index / (gradient.positions.length - 1);
    let color = 0; for (const shift of [16, 8, 0]) color |= Math.round(((gradient.from >> shift) & 255) * (1 - t) + ((gradient.to >> shift) & 255) * t) << shift;
    out[position] = { ...out[position], color };
  });
  return out;
}
export function RichName({ value, fallback = '称号预览' }) {
  const chars = glyphs(value);
  return <span className="rich-name">{chars.length ? chars.map((char, index) => {
    const channels = [16,8,0].map(shift => ((char.color >> shift) & 255) / 255).map(c => c <= .04045 ? c/12.92 : ((c+.055)/1.055)**2.4);
    const pale = channels[0]*.2126 + channels[1]*.7152 + channels[2]*.0722 > .4;
    return <span key={index} className={pale ? 'pale-glyph' : undefined} style={{ color: hex(char.color), fontWeight: char.bold ? 700 : 400, fontStyle: char.italic ? 'italic' : 'normal', textDecoration: [char.underline && 'underline', char.strike && 'line-through'].filter(Boolean).join(' ') || 'none' }}>{char.text}</span>;
  }) : <span className="muted">{fallback}</span>}</span>;
}
