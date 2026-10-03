'use strict';
const api = globalThis.browser ?? globalThis.chrome;
const $ = id => document.getElementById(id);
const P = globalThis.BOUCLIER_PLATFORM || { ios: false, device: 'this Mac', extensionSettings: 'Safari Settings → Extensions → Bouclier' };
const I = globalThis.BOUCLIER_I18N || { t: key => key, lang: () => 'en', locale: () => undefined, apply: () => {} };
const t = I.t;

I.apply();

let tab = null;
let state = null;

// Every change of rules makes Safari compile all of Bouclier's rules again (about 20 s in WebKit on an
// idle MacBook Pro, tests/webkit-bench/diag-dnr.js): say so, and that the page reloads by itself.
const RECOMPILE = t('popup_recompile');
const RECOMPILE_RELOAD = t('popup_recompile_reload');

function send(message) {
  return api.runtime.sendMessage(message);
}

// The count after a reload. A change that reloads the page (site switch, timed pause, site controls)
// read the count while the new page was only starting, so the menu said "0 requests blocked" (seen in
// WebKit, 3 Oct 2026). It is read again once the page has loaded, and once more for late ads; only the
// count and today's line are redrawn, and not while the keyboard is on an address or a question is open.
let countTimers = [];
function refreshCountSoon() {
  countTimers.forEach(clearTimeout);
  countTimers = [1000, 3500].map(ms => setTimeout(refreshCount, ms));
}

async function refreshCount() {
  if (!tab || !state || document.body.getAttribute('aria-busy') === 'true') return;
  const detail = $('detail');
  if (!$('allow-confirm').hidden || (document.activeElement && detail.contains(document.activeElement))) return;
  let next = null;
  try { next = await send({ type: 'popup:state', tabId: tab.id }); } catch { return; }
  if (!next || next.host !== state.host || next.paused !== state.paused || next.sitePaused !== state.sitePaused) return;
  Object.assign(state, { blocked: next.blocked, breakdown: next.breakdown, topHosts: next.topHosts, today: next.today, todayPopups: next.todayPopups });
  renderCount();
  renderToday();
}

if (api.tabs && api.tabs.onUpdated) {
  api.tabs.onUpdated.addListener((tabId, info) => {
    if (tab && tabId === tab.id && info.status === 'complete') refreshCountSoon();
  });
}

function setStatus(text, busy = false) {
  const el = $('status');
  el.textContent = text || '';
  el.classList.toggle('busy', !!busy);
}

function timeLabel(ts) {
  const d = new Date(ts);
  const sameDay = d.toDateString() === new Date().toDateString();
  const time = d.toLocaleTimeString(I.locale(), { hour: '2-digit', minute: '2-digit' });
  return sameDay ? time : `${d.toLocaleDateString(I.locale(), { weekday: 'short' })} ${time}`;
}

/** The text for one or for several, by the language's rule (French says "0 élément", English "0 elements"). */
function plural(n, one, other) {
  let form = n === 1 ? 'one' : 'other';
  try { form = new Intl.PluralRules(I.lang()).select(n); } catch { /* no Intl: the English rule */ }
  return form === 'one' ? one : other;
}

/** A filter list's name in the device's language (the catalogue's English name when there is no translation). */
function listName(l) {
  const s = t(`list_${l.id}_name`);
  return s === `list_${l.id}_name` ? l.name : s;
}

function listRow(item) {
  const li = document.createElement('li');
  const text = document.createElement('div');
  text.className = 'text';
  const title = document.createElement('div');
  title.className = 'row-title';
  title.textContent = item.name;
  const desc = document.createElement('div');
  desc.className = 'row-desc';
  desc.textContent = item.description;
  text.append(title, desc);
  if (item.note) {
    const note = document.createElement('div');
    note.className = 'note';
    note.textContent = item.note;
    text.append(note);
  }
  const label = document.createElement('label');
  label.className = 'switch';
  const input = document.createElement('input');
  input.type = 'checkbox';
  input.checked = item.enabled;
  input.disabled = !!item.disabled;
  input.setAttribute('aria-label', item.name);
  input.addEventListener('change', () => item.onToggle(input));
  const knob = document.createElement('span');
  label.append(input, knob);
  li.append(text, label);
  return li;
}

async function run(label, message, { reload = false } = {}) {
  setStatus(label, true);
  document.body.setAttribute('aria-busy', 'true');
  try {
    // the background reloads the page once the change is applied, even if this menu is closed meanwhile
    const res = await send(reload && tab ? Object.assign({}, message, { reloadTabId: tab.id }) : message);
    if (res && res.ok === false) throw new Error(res.error || t('popup_not_possible'));
    await refresh();
    setStatus(reload ? t('popup_done_reloaded') : t('popup_done'));
    if (reload) refreshCountSoon();
  } catch (err) {
    setStatus(t('popup_error', String(err && err.message || err)));
  } finally {
    document.body.removeAttribute('aria-busy');
  }
}

const SHORT = {
  ads: t('popup_short_ads'),
  privacy: t('popup_short_privacy'),
  french: t('popup_short_french'),
  safety: t('popup_short_safety'),
  urlclean: t('popup_short_urlclean'),
  antiadblock: t('popup_short_antiadblock'),
  cookies: t('popup_short_cookies'),
  social: t('popup_short_social'),
  annoyances: t('popup_short_annoyances'),
};

function listFor(l) {
  const name = listName(l);
  return {
    name,
    description: SHORT[l.id] || l.description,
    enabled: l.enabled,
    disabled: state.paused,
    // only a list left out for Safari's rule budget says so; one that failed to apply shows the error line instead
    note: l.enabled && !state.paused && (state.overBudget || []).includes(l.id) ? t('popup_waiting_room') : '',
    onToggle: () => run(`${l.enabled ? t('popup_turning_off', name) : t('popup_turning_on', name)} ${RECOMPILE}`,
      { type: 'popup:toggleList', id: l.id }),
  };
}

function optionItem(key, name, description) {
  return {
    name,
    description,
    enabled: !!state[key],
    disabled: state.paused,
    onToggle: input => run(input.checked ? t('popup_turning_on', name) : t('popup_turning_off', name), { type: 'popup:setOption', key, value: input.checked }),
  };
}

function renderCount() {
  const count = $('count');
  const detail = $('detail');
  count.textContent = '';
  detail.textContent = '';
  if (!state.host) return;
  if (state.paused || state.sitePaused) {
    if (state.paused) {
      count.textContent = state.pausedUntil ? t('popup_count_paused_all_until', timeLabel(state.pausedUntil)) : t('popup_count_paused_all');
    } else {
      count.textContent = state.sitePausedUntil
        ? t('popup_count_paused_site_until', timeLabel(state.sitePausedUntil))
        : t('popup_count_paused_site');
    }
    return;
  }
  if (typeof state.blocked !== 'number') {
    count.textContent = t('popup_protection_on');
    return;
  }
  const strong = document.createElement('strong');
  strong.textContent = state.blocked.toLocaleString(I.locale());
  count.append(strong, document.createTextNode(' ' + plural(state.blocked, t('popup_requests_blocked_one'), t('popup_requests_blocked_other'))));

  // What was blocked: top addresses in Safari, categories where only those are known.
  const tags = [];
  if (state.topHosts && state.topHosts.length) {
    state.topHosts.forEach(h => tags.push([h.host, h.n]));
  } else if (state.breakdown) {
    for (const [cat, n] of Object.entries(state.breakdown)) {
      if (cat === 'links' || cat === 'popups' || !n) continue;
      tags.push([state.labels[cat] || cat, n]);
    }
  }
  const hostTags = !!(state.topHosts && state.topHosts.length);
  for (const [label, n] of tags) {
    const tag = document.createElement(hostTags ? 'button' : 'span');
    tag.className = 'tag';
    const b = document.createElement('b');
    b.textContent = n;
    tag.append(document.createTextNode(label + ' '), b);
    if (hostTags) {
      tag.title = t('popup_tag_title', label, state.site);
      tag.addEventListener('click', () => askAllow(label, tag));
    }
    detail.append(tag);
  }
  if (hostTags) {
    const hint = document.createElement('div');
    hint.className = 'hint';
    hint.textContent = t(P.ios ? 'popup_hint_tap' : 'popup_hint_click');   // iPhone and iPad: touch words
    detail.append(hint);
  }
  const links = state.breakdown && state.breakdown.links;
  if (links) {
    const note = document.createElement('div');
    note.textContent = links === 1 ? t('popup_links_one') : t('popup_links_other', links);
    detail.append(note);
  }
}

function askAllow(host, from) {
  const box = $('allow-confirm');
  $('allow-text').textContent = t('popup_allow_confirm', host, state.site);
  box.hidden = false;
  // the keyboard lands on the question's answer, and Escape (or Cancel) goes back to the address
  const close = () => {
    box.hidden = true;
    box.onkeydown = null;
    if (from && from.isConnected) from.focus();
  };
  $('allow-yes').onclick = () => {
    box.hidden = true;
    box.onkeydown = null;
    run(t('popup_allowing', host, state.site), { type: 'popup:allowHost', host, site: state.site }, { reload: true });
  };
  $('allow-no').onclick = close;
  box.onkeydown = e => {
    if (e.key === 'Escape') {
      e.preventDefault();
      e.stopPropagation();
      close();
    }
  };
  $('allow-yes').focus();
}

function renderControls() {
  const ul = $('controls');
  ul.textContent = '';
  const c = state.siteControls || {};
  const items = [
    ['fonts', t('popup_ctl_fonts'), t('popup_ctl_fonts_desc')],
    ['scripts3p', t('popup_ctl_scripts3p'), t('popup_ctl_scripts3p_desc')],
    ['comments', t('popup_ctl_comments'), t('popup_ctl_comments_desc')],
  ];
  for (const [key, name, description] of items) {
    ul.append(listRow({
      name,
      description,
      enabled: !!c[key],
      disabled: state.paused || state.sitePaused,
      onToggle: input => run(input.checked ? t('popup_turning_on_site', name, state.site) : t('popup_turning_off_site', name, state.site),
        { type: 'popup:siteControl', host: state.host, key, value: input.checked }, { reload: true }),
    }));
  }
  const active = items.filter(([key]) => c[key]).length;
  $('site-controls').querySelector('summary').textContent = active
    ? plural(active, t('popup_site_controls_on_one', active), t('popup_site_controls_on_other', active))
    : t('popup_site_controls');
}

function renderToday() {
  const todayBits = [];
  if (typeof state.today === 'number') {
    const n = state.today.toLocaleString(I.locale());
    todayBits.push(plural(state.today, t('popup_today_blocked_one', n), t('popup_today_blocked_other', n)));
  }
  if (state.todayPopups) todayBits.push(plural(state.todayPopups, t('popup_today_popups_one', state.todayPopups), t('popup_today_popups_other', state.todayPopups)));
  $('today').textContent = todayBits.join(' · ') || t('popup_version', state.version);
}

function render() {
  const web = !!state.host;
  renderToday();

  $('host').textContent = web ? state.site : t('popup_no_website');
  const siteOn = web && !state.sitePaused && !state.paused;
  $('site-toggle').checked = siteOn;
  $('site-toggle').disabled = !web || state.paused;
  $('site-card').classList.toggle('off', !siteOn);
  renderCount();

  // hiding an element needs access to this page only, so a site allowed on its own is enough
  $('pick').disabled = !web || !siteOn || !(state.hostAccess || state.siteAccess);
  // timed pause for this site only: shown while the site is protected (the switch resumes it)
  $('site-pause').hidden = !web || !siteOn;

  $('hidden-note').hidden = !(web && state.hiddenCount);
  $('hidden-text').textContent = plural(state.hiddenCount, t('popup_hidden_one', state.hiddenCount), t('popup_hidden_other', state.hiddenCount));

  $('site-controls').hidden = !web;
  if (web) renderControls();

  $('access-banner').hidden = state.hostAccess;
  $('paused-banner').hidden = !state.paused;
  $('paused-text').textContent = state.pausedUntil
    ? t('popup_paused_banner_until', timeLabel(state.pausedUntil))
    : t('popup_paused_banner');
  $('pause-all').checked = state.paused;
  $('pause-chips').hidden = state.paused;
  $('pause-desc').textContent = state.paused && state.pausedUntil
    ? t('popup_back_on_at', timeLabel(state.pausedUntil))
    : t('popup_pause_all_desc');

  const over = state.overBudget || [];
  const budget = $('budget-banner');
  if (over.length) {
    const names = state.lists.filter(l => over.includes(l.id)).map(listName).join(', ');
    budget.textContent = t('popup_budget', names);
    budget.hidden = false;
  } else {
    budget.hidden = true;
  }
  const err = $('error-banner');
  if (state.lastError && Date.now() - state.lastError.at < 10 * 60 * 1000) {
    err.textContent = t('popup_last_change_failed', String(state.lastError.message));
    err.hidden = false;
  } else {
    err.hidden = true;
  }

  const main = $('lists');
  const more = $('lists-more');
  main.textContent = '';
  more.textContent = '';
  const byId = Object.fromEntries(state.lists.map(l => [l.id, l]));
  const seen = new Set();
  const addList = (ul, id) => {
    if (byId[id]) {
      seen.add(id);
      ul.append(listRow(listFor(byId[id])));
    }
  };
  addList(main, 'ads');
  addList(main, 'privacy');
  addList(main, 'french');
  main.append(listRow({
    name: t('popup_youtube_ads'),
    description: t('popup_youtube_ads_desc'),
    enabled: state.youtube,
    disabled: state.paused,
    onToggle: () => run(state.youtube ? t('popup_youtube_off') : t('popup_youtube_on'), { type: 'popup:toggleYoutube' }),
  }));
  main.append(listRow(optionItem('popupBlocker', t('popup_popup_ads'), t('popup_popup_ads_desc'))));
  addList(main, 'safety');
  addList(main, 'urlclean');
  let extraOn = false;
  for (const l of state.lists) {
    if (seen.has(l.id)) continue;
    if (l.enabled) extraOn = true;
    more.append(listRow(listFor(l)));
  }
  more.append(listRow(optionItem('youtubeHideShorts', t('popup_hide_shorts'), t('popup_hide_shorts_desc'))));
  if (state.youtubeHideShorts) extraOn = true;
  if (extraOn) $('more').open = true;
}

async function refresh() {
  state = await send({ type: 'popup:state', tabId: tab ? tab.id : null });
  render();
}

async function main() {
  const tabs = await api.tabs.query({ active: true, currentWindow: true });
  tab = tabs[0] || null;
  await refresh();

  $('site-toggle').addEventListener('change', () => {
    const turningOff = !$('site-toggle').checked;
    // say what the user chose, so a pause that ended while this menu was open is never flipped
    run(`${turningOff ? t('popup_pausing_site', state.site) : t('popup_protecting_again', state.site)} ${RECOMPILE_RELOAD}`,
      { type: 'popup:setSitePaused', host: state.host, paused: turningOff }, { reload: true });
  });
  for (const chip of document.querySelectorAll('#site-pause .chip')) {
    chip.addEventListener('click', () => {
      const msg = { type: 'popup:pauseSiteFor', host: state.host };
      if (chip.dataset.until) msg.until = chip.dataset.until;
      else msg.minutes = Number(chip.dataset.minutes);
      const what = chip.dataset.until
        ? t('popup_pausing_site_tomorrow', state.site)
        : t('popup_pausing_site_for', state.site, chip.textContent);
      run(`${what} ${RECOMPILE_RELOAD}`, msg, { reload: true });
    });
  }
  $('pause-all').addEventListener('change', () => {
    const pause = $('pause-all').checked;
    run(pause ? t('popup_pausing_all') : t('popup_resuming'), { type: 'popup:setPause', paused: pause }, { reload: true });
  });
  $('resume').addEventListener('click', () => {
    run(t('popup_resuming'), { type: 'popup:setPause', paused: false }, { reload: true });
  });
  for (const chip of document.querySelectorAll('#pause-chips .chip')) {
    chip.addEventListener('click', () => {
      const msg = chip.dataset.until
        ? { type: 'popup:pauseAllFor', until: chip.dataset.until }
        : { type: 'popup:pauseAllFor', minutes: Number(chip.dataset.minutes) };
      run(chip.dataset.until ? t('popup_pausing_all_tomorrow') : t('popup_pausing_all_for', chip.textContent.toLowerCase()), msg, { reload: true });
    });
  }
  $('unhide').addEventListener('click', () => {
    run(t('popup_unhiding', state.site), { type: 'popup:unhideSite', host: state.host }, { reload: true });
  });
  $('pick').addEventListener('click', async () => {
    try {
      const res = await send({ type: 'popup:picker', tabId: tab.id });
      if (res && res.ok === false) throw new Error('picker');
      window.close();
    } catch {
      setStatus(t('popup_picker_failed'));
    }
  });
  $('open-settings').addEventListener('click', async () => {
    try { await api.runtime.openOptionsPage(); } catch { /* Safari opens it anyway */ }
    window.close();
  });
  $('setup').addEventListener('click', async () => {
    try { await api.tabs.create({ url: api.runtime.getURL('pages/welcome.html') }); } catch { /* no tab */ }
    window.close();
  });
  $('grant').addEventListener('click', async () => {
    // Safari only asks during a click, so this has to be the first thing the click does
    try { await api.permissions.request({ origins: ['<all_urls>'] }); } catch { /* not asked: the steps below */ }
    try { await refresh(); } catch { /* keep what is shown */ }
    if (state.hostAccess) {
      setStatus(t('popup_thanks'));
      return;
    }
    // Safari did not ask, or the answer was not "every website": say where to allow it by hand
    $('access-text').textContent = `${t('popup_access_denied')} ${P.accessSteps || t('popup_access_open_settings', P.extensionSettings)}`;
    $('grant').hidden = true;
  });

  if (state.applying) setStatus(t('popup_applying'), true);
}

main().catch(err => setStatus(t('popup_load_failed', String(err && err.message || err))));
