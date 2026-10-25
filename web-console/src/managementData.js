const units = {s:1000,m:60000,h:3600000};
export function shutdownDelay(amount, unit) {
  const value = Number(amount), multiplier = units[unit];
  return Number.isSafeInteger(value) && value > 0 && multiplier && value * multiplier <= 86400000 ? value * multiplier : null;
}
export function stopPhase(plan, now) {
  return !plan ? 'none' : now < plan.kickAt ? 'pending' : now < plan.stopAt ? 'maintenance' : 'complete';
}
export function countdown(milliseconds) {
  const seconds = Math.max(0, Math.ceil(milliseconds / 1000));
  return `${String(Math.floor(seconds / 3600)).padStart(2,'0')}:${String(Math.floor(seconds / 60) % 60).padStart(2,'0')}:${String(seconds % 60).padStart(2,'0')}`;
}
