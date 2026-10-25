import React, { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { CalendarBlank, CaretDown, CaretLeft, CaretRight } from '@phosphor-icons/react';
import { useFloating } from './ui';

const weekdays = ['一', '二', '三', '四', '五', '六', '日'];
const pad = value => String(value).padStart(2, '0');
const civilDate = (year, month, day = 1) => {
  const date = new Date(0);
  date.setHours(12, 0, 0, 0);
  date.setFullYear(year, month, day);
  return date;
};
const isoDate = date => `${String(date.getFullYear()).padStart(4, '0')}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
function parseDate(value) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return null;
  const [year, month, day] = value.split('-').map(Number);
  if (year < 1 || year > 9999 || month < 1 || month > 12 || day < 1 || day > 31) return null;
  const date = civilDate(year, month - 1, day);
  return isoDate(date) === value ? date : null;
}
const todayValue = () => new Intl.DateTimeFormat('sv-SE', {
  timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
}).format(new Date());

export function DateField({ label, value = '', onChange, disabled = false }) {
  const inputId = React.useId(), calendarId = React.useId();
  const input = useRef(null), cells = useRef(new Map());
  const initial = parseDate(value) || parseDate(todayValue());
  const [open, setOpen] = useState(false), [monthPicker, setMonthPicker] = useState(false);
  const [month, setMonth] = useState(() => ({ year: initial.getFullYear(), index: initial.getMonth() }));
  const [yearText, setYearText] = useState(String(initial.getFullYear()));
  const [focused, setFocused] = useState(isoDate(initial)), [focusGrid, setFocusGrid] = useState(false);
  const close = () => { setOpen(false); setMonthPicker(false); input.current?.focus(); };
  const floating = useFloating(open, close, 294);

  useEffect(() => {
    input.current?.setCustomValidity(!value || parseDate(value) ? '' : '请输入有效日期，格式为 YYYY-MM-DD。');
  }, [value]);
  useEffect(() => { if (disabled) setOpen(false); }, [disabled]);
  useEffect(() => setYearText(String(month.year)), [month.year]);
  useLayoutEffect(() => {
    if (open && focusGrid && !monthPicker) cells.current.get(focused)?.focus();
  }, [open, focused, focusGrid, monthPicker, month.year, month.index]);

  const show = () => {
    const date = parseDate(value) || parseDate(todayValue());
    setMonth({ year: date.getFullYear(), index: date.getMonth() });
    setFocused(isoDate(date)); setMonthPicker(false); setFocusGrid(true); setOpen(true);
  };
  const choose = date => { onChange(date); close(); };
  const moveMonth = delta => {
    const date = civilDate(month.year, month.index + delta);
    if (date.getFullYear() < 1 || date.getFullYear() > 9999) return;
    setMonth({ year: date.getFullYear(), index: date.getMonth() });
    setFocused(isoDate(date)); setFocusGrid(false);
  };
  const moveDay = (event, date) => {
    const offsets = { ArrowLeft: -1, ArrowRight: 1, ArrowUp: -7, ArrowDown: 7 };
    let next;
    if (event.key in offsets) next = civilDate(date.getFullYear(), date.getMonth(), date.getDate() + offsets[event.key]);
    else if (event.key === 'Home' || event.key === 'End') {
      const weekday = (date.getDay() + 6) % 7;
      next = civilDate(date.getFullYear(), date.getMonth(), date.getDate() + (event.key === 'Home' ? -weekday : 6 - weekday));
    } else if (event.key === 'PageUp' || event.key === 'PageDown') {
      const offset = event.key === 'PageUp' ? -1 : 1;
      const last = civilDate(date.getFullYear(), date.getMonth() + offset + 1, 0).getDate();
      next = civilDate(date.getFullYear(), date.getMonth() + offset, Math.min(date.getDate(), last));
    } else return;
    event.preventDefault();
    if (next.getFullYear() < 1 || next.getFullYear() > 9999) return;
    setFocused(isoDate(next)); setMonth({ year: next.getFullYear(), index: next.getMonth() }); setFocusGrid(true);
  };
  const applyYear = () => {
    const year = Number(yearText);
    if (/^\d{1,4}$/.test(yearText) && year >= 1 && year <= 9999) {
      setMonth(old => ({ ...old, year }));
      setFocused(isoDate(civilDate(year, month.index))); setYearText(String(year));
    } else setYearText(String(month.year));
  };
  const first = civilDate(month.year, month.index);
  const offset = (first.getDay() + 6) % 7;
  const days = Array.from({ length: 42 }, (_, index) => civilDate(month.year, month.index, index - offset + 1));
  const today = todayValue();

  return <div className="field-group date-field">
    <label className="field-label" htmlFor={inputId}>{label}</label>
    <div className={`date-input ${open ? 'date-open' : ''}`} ref={floating.anchor}>
      <input id={inputId} ref={input} type="text" placeholder="YYYY-MM-DD" maxLength={10} autoComplete="off"
        value={value} disabled={disabled} pattern="[0-9]{4}-[0-9]{2}-[0-9]{2}"
        aria-describedby={inputId + '-format'}
        onChange={event => onChange(event.target.value)}
        onKeyDown={event => { if (event.altKey && event.key === 'ArrowDown') { event.preventDefault(); show(); } }} />
      <button type="button" className="calendar-trigger" aria-label={label + '选择日期'} aria-haspopup="dialog"
        aria-expanded={open} aria-controls={calendarId} disabled={disabled} onClick={() => open ? close() : show()}><CalendarBlank size={17} /></button>
    </div>
    <span id={inputId + '-format'} className="visually-hidden">日期格式：年-月-日，例如 2026-10-23。Alt 加下箭头打开日历。</span>
    {open && createPortal(<div ref={floating.panel} id={calendarId} role="dialog" aria-label={label + '日历'}
      className="themed-popup date-calendar" style={floating.position}>
      <div className="calendar-heading">
        <button type="button" className="calendar-month-label" aria-label="选择年月" aria-expanded={monthPicker}
          onClick={() => { setMonthPicker(v => !v); setFocusGrid(false); }}>{month.year}年{month.index + 1}月<CaretDown size={12} /></button>
        <div className="calendar-navigation">
          <button type="button" aria-label={monthPicker ? '上一年' : '上个月'} disabled={monthPicker ? month.year <= 1 : month.year === 1 && month.index === 0}
            onClick={() => monthPicker ? setMonth(old => ({ ...old, year: old.year - 1 })) : moveMonth(-1)}><CaretLeft size={15} /></button>
          <button type="button" aria-label={monthPicker ? '下一年' : '下个月'} disabled={monthPicker ? month.year >= 9999 : month.year === 9999 && month.index === 11}
            onClick={() => monthPicker ? setMonth(old => ({ ...old, year: old.year + 1 })) : moveMonth(1)}><CaretRight size={15} /></button>
        </div>
      </div>
      {monthPicker ? <div className="calendar-month-picker">
        <label className="calendar-year-label">年份<input aria-label="选择年份" type="text" inputMode="numeric" maxLength={4}
          value={yearText} onChange={event => setYearText(event.target.value)} onBlur={applyYear}
          onKeyDown={event => { if (event.key === 'Enter') { event.preventDefault(); applyYear(); } }} /></label>
        <div className="calendar-months">{Array.from({ length: 12 }, (_, index) => <button type="button" key={index}
          aria-pressed={index === month.index} className={index === month.index ? 'selected' : ''}
          onClick={() => { setMonth(old => ({ ...old, index })); setFocused(isoDate(civilDate(month.year, index))); setMonthPicker(false); setFocusGrid(true); }}>{index + 1}月</button>)}</div>
      </div> : <>
        <div className="calendar-weekdays" aria-hidden="true">{weekdays.map(day => <span key={day}>{day}</span>)}</div>
        <div className="calendar-days" role="group" aria-label={month.year + '年' + (month.index + 1) + '月日期'}>
          {days.map(date => { const key = isoDate(date), valid = date.getFullYear() >= 1 && date.getFullYear() <= 9999; return <button
            type="button" key={key} ref={element => { if (element) cells.current.set(key, element); else cells.current.delete(key); }}
            aria-label={key} aria-pressed={key === value} aria-current={key === today ? 'date' : undefined} disabled={!valid}
            tabIndex={key === focused ? 0 : -1} className={[date.getMonth() !== month.index && 'other-month', key === today && 'today', key === value && 'selected'].filter(Boolean).join(' ')}
            onKeyDown={event => moveDay(event, date)} onClick={() => choose(key)}>{date.getDate()}</button>; })}
        </div>
      </>}
      <div className="calendar-footer"><button type="button" onClick={() => choose('')}>清除</button><button type="button" onClick={() => choose(today)}>今天</button></div>
    </div>, document.querySelector('.app'))}
  </div>;
}
