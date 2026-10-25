import React, { useState } from 'react';
import { Moon, Sun } from '@phosphor-icons/react';
import { Toggle } from './App';
import { Performance } from './Performance';
import { WorldView } from './WorldView';
import { PlayerRoster } from './PlayerRoster';
import {Archives} from './Archives';
import { Management, initialManagement } from './Management';

function PanelHeading({ children, note }) { return <div className="detail-section-heading"><h2>{children}</h2>{note && <span>{note}</span>}</div>; }

export function DetailView({ page, telemetry: t, preferences, setPreferences, navigate, inspect, toast }) {
  const [management, setManagement] = useState(initialManagement);

  if (page === 'performance') return <Performance telemetry={t} preferences={preferences}/>;

  if (page === 'players') return <PlayerRoster telemetry={t} inspect={inspect}/>;

  if (page === 'worlds') return <WorldView telemetry={t}/>;

  if (page === 'management') return <Management telemetry={t} state={t.management?{...initialManagement,...t.management,maintenance:t.management.maintenance?.state==='ACTIVE',restart:t.management.restartStatus?.includes('已预约'),stop:t.management.stop?{kickAt:t.management.stop.kickTimeMillis,stopAt:t.management.stop.stopTimeMillis}:null}:management} setState={setManagement} toast={toast}/>;

  return <div className="detail-scroll"><div className="detail-inner system-settings"><div className="preferences-panel"><div className="setting-row"><div><strong>界面主题</strong><p>选择此浏览器使用的外观。</p></div><div className="theme-segment" aria-label="外观主题"><button className={preferences.theme === 'dark' ? 'selected' : ''} aria-pressed={preferences.theme === 'dark'} onClick={() => setPreferences(p => ({ ...p, theme: 'dark' }))}><Moon size={14}/>深色</button><button className={preferences.theme === 'light' ? 'selected' : ''} aria-pressed={preferences.theme === 'light'} onClick={() => setPreferences(p => ({ ...p, theme: 'light' }))}><Sun size={14}/>浅色</button></div></div><Toggle label="称号模块入口" description="在侧栏与主页显示称号功能。" checked={preferences.titles} change={value => setPreferences(p => ({ ...p, titles: value }))}/></div><Archives toast={toast}/></div></div>;
}
