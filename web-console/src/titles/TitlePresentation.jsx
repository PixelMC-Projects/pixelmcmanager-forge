import React from 'react';
import { hex } from './api';
import { HubTitleCardPreview } from './HubTitleCardPreview';
import { Select } from './ui';
import presets from './titlePresentationPresets.json';
import './titlePresentation.css';

export const defaultPresentation = () => ({ badge: 'leaf', rarity: 'none', theme: 'slate' });
export function validatePresentation(value) {
  for (const [field, group] of [['badge', 'badges'], ['rarity', 'rarities'], ['theme', 'themes']]) {
    if (!presets[group].some(item => item.id === value?.[field])) throw new Error('请选择有效的卡片徽章、稀有度和主题预设。');
  }
}
export const TitleCardPreview = HubTitleCardPreview;
export function PresentationFields({ title, onChange, disabled }) {
  const value = { ...defaultPresentation(), ...title.presentation };
  const select = (field, group, label) => <div className="field-group"><span className="field-label">{label}</span><Select label={label} value={value[field]} onChange={next => onChange({ ...value, [field]: next })} disabled={disabled} options={presets[group].map(item => ({ value: item.id, label: field === 'theme' ? <span className="title-theme-option"><span aria-hidden="true" className="title-theme-swatch" style={{background:hex(item.accent)}}/>{item.label}</span> : item.label || '不标注' }))}/></div>;
  return <section className="title-presentation-fields"><div className="section-heading"><span>玩家卡片样式</span><span className="small-muted">实时预览</span></div><div className="form-columns">{select('badge', 'badges', '卡片徽章')}{select('rarity', 'rarities', '稀有度')}</div>{select('theme', 'themes', '卡片主题')}<TitleCardPreview title={title}/><p className="field-help">徽章、稀有度和主题用于玩家卡片展示；称号文字沿用原有颜色与样式。</p></section>;
}
