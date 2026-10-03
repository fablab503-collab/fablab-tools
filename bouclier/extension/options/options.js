'use strict';
const api = globalThis.browser ?? globalThis.chrome;
const $ = id => document.getElementById(id);
const SVG = 'http://www.w3.org/2000/svg';
const P = globalThis.BOUCLIER_PLATFORM || { ios: false, device: 'this Mac', extensionSettings: 'Safari Settings → Extensions → Bouclier' };
const I = globalThis.BOUCLIER_I18N || { t: key => key, lang: () => 'en', locale: () => undefined, apply: () => {} };
const t = I.t;
const LANG = I.locale(); // French formats in French; in English the device's own regional formats
// French says "0 pop-up fermé", "1 pop-up fermé", English "1 …": the texts ending in _one are for those counts
const PLURAL = new Intl.PluralRules(LANG);
const isOne = n => PLURAL.select(n) === 'one';

I.apply(); // first: the texts it fills hold the .device placeholder that is filled next
document.querySelectorAll('.device').forEach(n => { n.textContent = P.device; });

// Fixed categorical order (never cycled): colour follows the category, not its rank.
const SERIES = [
  ['blocked', '--s1'], ['ads', '--s1'], ['trackers', '--s2'], ['malware', '--s3'],
  ['annoyances', '--s4'], ['custom', '--s5'], ['site', '--s6'], ['other', '--s7'],
];

const STALE_DAYS = 45; // App Store updates bring new lists; warn when one is overdue

function el(tag, props = {}, children = []) {
  const node = document.createElement(tag);
  Object.assign(node, props);
  for (const c of [].concat(children)) node.append(c instanceof Node ? c : document.createTextNode(String(c)));
  return node;
}

function svg(tag, attrs = {}) {
  const node = document.createElementNS(SVG, tag);
  for (const [k, v] of Object.entries(attrs)) node.setAttribute(k, v);
  return node;
}

/** A text with nodes (a link, a number in bold) in its {0}, {1}… slots, as children for el(). */
function withNodes(text, ...nodes) {
  return text.split(/\{(\d)\}/).map((s, i) => (i % 2 ? nodes[s] : s)).filter(x => x !== '' && x !== undefined);
}

/** A filter list's name in the device's language (the catalogue's English name when there is no translation). */
function listName(l) {
  const s = t(`list_${l.id}_name`);
  return s === `list_${l.id}_name` ? l.name : s;
}

/** A filter list's description in the device's language (the catalogue's English one when there is no translation). */
function listDescription(l) {
  const s = t(`list_${l.id}_desc`);
  return s === `list_${l.id}_desc` ? l.description : s;
}

/** Licence names (CC BY-SA 3.0…) stay as they are; the lists that are Bouclier's own say so in words. */
function licenceName(l) {
  return l.license === 'Part of Bouclier' ? t('options_license_own') : l.license;
}

function formatBytes(n) {
  // the digits toFixed() gives (no thousands separator), with a decimal comma in French
  const num = (x, digits) => x.toLocaleString(LANG, { minimumFractionDigits: digits, maximumFractionDigits: digits, useGrouping: false });
  if (n >= 1e9) return t('options_size_gb', num(n / 1e9, 1));
  if (n >= 1e6) return t('options_size_mb', num(n / 1e6, n >= 1e8 ? 0 : 1));
  return t('options_size_kb', num(Math.round(n / 1e3), 0));
}

function send(message) {
  return api.runtime.sendMessage(message);
}

/* ------------------------------------------------------------- stats */

function renderStats(stats) {
  $('stat-today').textContent = stats.today.toLocaleString(LANG);
  $('stat-week').textContent = stats.week.toLocaleString(LANG);
  $('stat-all').textContent = stats.all.toLocaleString(LANG);
  $('stat-bytes').textContent = formatBytes(stats.bytesSaved);
  $('stat-bytes-note').textContent = t('options_bytes_note', Math.round(stats.bytesPerBlock / 1024));
  const extra = [];
  const { popups, links } = stats.totals;
  if (popups) extra.push(isOne(popups) ? t('options_popups_closed_one', popups.toLocaleString(LANG)) : t('options_popups_closed', popups.toLocaleString(LANG)));
  if (links) extra.push(isOne(links) ? t('options_links_cleaned_one', links.toLocaleString(LANG)) : t('options_links_cleaned', links.toLocaleString(LANG)));
  $('stats-since').textContent = t('options_counting_since', new Date(stats.since).toLocaleDateString(LANG)) + (extra.length ? ` · ${extra.join(' · ')}` : '') + '. ' + t('options_stats_stay', P.device);

  // which series actually have data
  const present = SERIES.filter(([key]) => stats.series.some(d => d.counts[key]));
  const series = present.length ? present : [['blocked', '--s1']];
  drawChart(stats, series);

  const legend = $('legend');
  legend.textContent = '';
  if (series.length >= 2) {
    for (const [key, color] of series) {
      const item = el('span', { textContent: stats.labels[key] || key });
      item.style.setProperty('--c', `var(${color})`);
      legend.append(item);
    }
  }

  const rank = (target, rows) => {
    target.textContent = '';
    if (!rows.length) {
      target.append(el('li', { className: 'empty', textContent: t('options_nothing_yet') }));
      return;
    }
    for (const [name, n] of rows) target.append(el('li', {}, [name, el('span', { className: 'n', textContent: n.toLocaleString(LANG) })]));
  };
  rank($('top-domains'), stats.topDomains || []);
  rank($('top-sites'), stats.topSites || []);
}

function drawChart(stats, series) {
  const box = $('chart');
  box.textContent = '';
  const W = Math.max(320, Math.round(box.getBoundingClientRect().width || 700));
  const H = 170;
  const pad = { l: 36, r: 6, t: 10, b: 22 };
  const days = stats.series;
  const totals = days.map(d => series.reduce((n, [k]) => n + (d.counts[k] || 0), 0));
  const max = Math.max(4, ...totals);
  const step = Math.pow(10, Math.floor(Math.log10(max)));
  const niceMax = Math.ceil(max / step) * step;
  const plotW = W - pad.l - pad.r;
  const plotH = H - pad.t - pad.b;
  const slot = plotW / days.length;
  const barW = Math.min(28, slot * 0.6);
  const root = svg('svg', { viewBox: `0 0 ${W} ${H}`, width: W, height: H });
  const plain = x => x.toLocaleString(LANG, { useGrouping: false }); // what String() gives in English, 2,5 in French
  // "17 sept." is wider than "Sep 17": on a phone-width chart French days are written 17/09 so they don't run together
  const dayFormat = LANG === 'fr' && slot < 24 ? { day: '2-digit', month: '2-digit' } : { day: 'numeric', month: 'short' };
  for (let i = 0; i <= 2; i++) {
    const v = (niceMax / 2) * i;
    const y = pad.t + plotH - (v / niceMax) * plotH;
    root.append(svg('line', { x1: pad.l, x2: W - pad.r, y1: y, y2: y, class: 'grid', 'stroke-width': 1 }));
    const label = svg('text', { x: pad.l - 6, y: y + 3, 'text-anchor': 'end', class: 'axis' });
    label.textContent = v >= 1000 ? t('options_axis_thousands', plain(Math.round(v / 100) / 10)) : plain(v);
    root.append(label);
  }
  const tip = el('div', { className: 'tip' });
  days.forEach((d, i) => {
    const x = pad.l + i * slot + (slot - barW) / 2;
    let y = pad.t + plotH;
    const segments = series.filter(([k]) => d.counts[k]);
    segments.forEach(([k, color], j) => {
      const h = (d.counts[k] / niceMax) * plotH;
      const top = j === segments.length - 1;
      const gap = j > 0 ? 2 : 0; // 2px surface gap between a segment and the one below it
      y -= h;
      const topY = y;
      const bottomY = y + h - gap;
      if (bottomY - topY <= 0.5) return;
      const r = top ? Math.min(4, bottomY - topY, barW / 2) : 0;
      // rounded data end on the top segment only, square at the baseline
      const path = `M${x},${bottomY} V${topY + r} Q${x},${topY} ${x + r},${topY} H${x + barW - r} Q${x + barW},${topY} ${x + barW},${topY + r} V${bottomY} Z`;
      root.append(svg('path', { d: path, fill: `var(${color})` }));
    });
    if ((days.length - 1 - i) % 2 === 0) { // every other day, counted back from today, so "Today" never collides
      const dt = new Date(d.day + 'T12:00:00');
      const label = svg('text', { x: x + barW / 2, y: H - 6, 'text-anchor': 'middle', class: 'axis' });
      label.textContent = i === days.length - 1 ? t('options_chart_today') : dt.toLocaleDateString(LANG, dayFormat);
      root.append(label);
    }
    const hit = svg('rect', { x: pad.l + i * slot, y: pad.t, width: slot, height: plotH, class: 'bar-hit' });
    hit.addEventListener('mouseenter', () => {
      tip.textContent = '';
      const dt = new Date(d.day + 'T12:00:00');
      tip.append(el('div', {}, [dt.toLocaleDateString(LANG, { weekday: 'long', day: 'numeric', month: 'long' })]));
      // "{0} blocked": the number in bold, wherever the language puts it
      const blocked = isOne(totals[i]) ? t('options_tip_blocked_one') : t('options_tip_blocked');
      tip.append(el('div', {}, withNodes(blocked, el('b', { textContent: totals[i].toLocaleString(LANG) }))));
      if (series.length > 1) {
        for (const [k] of series) if (d.counts[k]) tip.append(el('div', {}, [t('options_tip_series', stats.labels[k] || k, d.counts[k].toLocaleString(LANG))]));
      }
      tip.style.display = 'block';
      // keep the tip inside the chart, so it never widens the page on a narrow iPhone
      const half = tip.offsetWidth / 2;
      const left = Math.min(Math.max(x + barW / 2, half), Math.max(half, box.clientWidth - half));
      tip.style.left = `${left}px`;
      tip.style.top = `${pad.t + 10}px`;
    });
    hit.addEventListener('mouseleave', () => { tip.style.display = 'none'; });
    root.append(hit);
  });
  box.append(root, tip);

  // table view: same numbers as text (relief for low-contrast colours and screen readers)
  const table = el('table');
  const head = el('tr', {}, [el('th', { textContent: t('options_table_day') }), ...series.map(([k]) => el('th', { className: 'num', textContent: stats.labels[k] || k })), el('th', { className: 'num', textContent: t('options_table_total') })]);
  table.append(el('thead', {}, [head]));
  const body = el('tbody');
  days.slice().reverse().forEach((d, idx) => {
    const i = days.length - 1 - idx;
    body.append(el('tr', {}, [el('td', { textContent: d.day }), ...series.map(([k]) => el('td', { className: 'num', textContent: (d.counts[k] || 0).toLocaleString(LANG) })), el('td', { className: 'num', textContent: totals[i].toLocaleString(LANG) })]));
  });
  table.append(body);
  const details = el('details', { className: 'table-view' }, [el('summary', { textContent: t('options_table_show') }), el('div', { className: 'table-wrap' }, [table])]);
  $('chart-table').replaceChildren(details);
}

/* -------------------------------------------------------------- general */

function toggleRow(name, description, checked, onChange) {
  const input = el('input', { type: 'checkbox', checked });
  input.setAttribute('aria-label', name);
  input.addEventListener('change', () => onChange(input.checked));
  return el('li', {}, [
    el('div', { className: 'text' }, [el('div', { textContent: name }), el('div', { className: 'desc', textContent: description })]),
    el('label', { className: 'switch' }, [input, el('span')]),
  ]);
}

function renderGeneral(settings) {
  const ul = $('general');
  ul.textContent = '';
  const opt = (key, name, description) => toggleRow(name, description, !!settings[key], async value => {
    await send({ type: 'options:setOption', key, value });
    reload();
  });
  ul.append(opt('popupBlocker', t('options_popup_blocker'), t('options_popup_blocker_desc')));
  ul.append(opt('showBadge', t('options_badge'), t('options_badge_desc')));
  ul.append(opt('youtube', t('options_youtube'), t('options_youtube_desc')));
  ul.append(opt('youtubeHideShorts', t('options_shorts'), t('options_shorts_desc')));
}

/* ----------------------------------------------------------------- load */

// Reloads the page's data after a change; a failure shows on the page instead of in the console.
function reload() {
  return load().catch(err => showLoadError(err));
}

function showLoadError(err) {
  const p = document.querySelector('p.load-error') || el('p', { className: 'errors load-error' });
  p.textContent = t('options_load_failed', err && err.message || err);
  document.querySelector('main').prepend(p);
}

// Filters typed but not saved yet are never replaced by a reload (flipping a switch reloads the page's data).
let customDirty = false;
$('custom').addEventListener('input', () => { customDirty = true; });

async function load() {
  const data = await send({ type: 'options:state' });
  const { settings, catalogue, state, stats } = data;
  const built = new Date(catalogue.generated * 1000);
  const ageDays = Math.floor((Date.now() - built.getTime()) / 86400000);
  $('version-line').textContent = t('options_version_line', data.version, built.toLocaleDateString(LANG));
  const stale = $('stale-notice');
  stale.hidden = ageDays <= STALE_DAYS;
  stale.textContent = t('options_stale', ageDays);

  renderStats(stats);
  renderGeneral(settings);

  // budget meter
  const used = (state && state.webkitRules) || 0;
  $('meter-fill').style.width = Math.min(100, (used / 150000) * 100).toFixed(1) + '%';
  $('meter-text').textContent = t('options_meter', used.toLocaleString(LANG));

  // lists
  const tbody = $('lists');
  tbody.textContent = '';
  const active = new Set((state && state.enabledLists) || []);
  const waiting = new Set((state && state.overBudget) || []);
  for (const l of catalogue.lists) {
    const on = settings.enabledLists.includes(l.id);
    let status;
    if (settings.paused) status = el('span', { className: 'status-off', textContent: t('options_status_paused') });
    else if (active.has(l.id)) status = el('span', { className: 'status-on', textContent: t('options_status_on') });
    else if (on && waiting.has(l.id)) status = el('span', { className: 'status-wait', textContent: t('options_status_waiting') });
    else status = el('span', { className: 'status-off', textContent: t('options_status_off') });
    const hiding = ((l.genericSelectors || 0) + (l.dynamicSelectors || 0)).toLocaleString(LANG);
    const siteCount = l.specificSites || 0;
    const hidingText = !siteCount ? hiding
      : isOne(siteCount) ? t('options_hiding_sites_one', hiding, siteCount.toLocaleString(LANG)) : t('options_hiding_sites', hiding, siteCount.toLocaleString(LANG));
    const source = /^https?:/.test(l.homepage || '') && l.source !== 'Bouclier'
      ? el('a', { href: l.homepage, textContent: l.source, target: '_blank', rel: 'noopener' })
      : el('span', { textContent: l.source });
    tbody.append(el('tr', {}, [
      el('td', {}, [el('strong', { textContent: listName(l) }), el('div', { className: 'muted small', textContent: listDescription(l) })]),
      el('td', {}, [status]),
      el('td', { className: 'num', textContent: l.webkitRules.toLocaleString(LANG) }),
      el('td', { className: 'num', textContent: hidingText }),
      el('td', {}, [source, el('div', { className: 'muted small', textContent: (l.version ? `v${l.version} · ` : '') + licenceName(l) })]),
    ]));
  }
  $('lists-date').textContent = t('options_lists_hint');

  // paused sites
  const sites = $('sites');
  sites.textContent = '';
  if (!settings.pausedSites.length) sites.append(el('li', { className: 'empty', textContent: t('options_none') }));
  for (const site of settings.pausedSites) {
    const until = settings.sitePauseUntil && settings.sitePauseUntil[site];
    const label = until ? t('options_paused_until', site, new Date(until).toLocaleTimeString(LANG, { hour: '2-digit', minute: '2-digit' })) : site;
    const again = t('options_protect_again', site);
    const btn = el('button', { title: again, textContent: '×', ariaLabel: again });
    btn.setAttribute('aria-label', again);
    btn.addEventListener('click', async () => {
      await send({ type: 'options:removeSite', site });
      reload();
    });
    sites.append(el('li', {}, [label, btn]));
  }

  // site controls
  const controls = $('controls');
  controls.textContent = '';
  const cEntries = Object.entries(settings.siteControls || {});
  if (!cEntries.length) controls.append(el('li', { className: 'empty', textContent: t('options_none') }));
  for (const [site, c] of cEntries) {
    const what = [c.fonts && t('options_control_fonts'), c.scripts3p && t('options_control_scripts'), c.comments && t('options_control_comments')].filter(Boolean).join(', ');
    const btn = el('button', { title: t('options_controls_remove'), textContent: '×', ariaLabel: t('options_controls_remove') });
    btn.setAttribute('aria-label', t('options_controls_remove_for', site));
    btn.addEventListener('click', async () => {
      await send({ type: 'options:removeSiteControls', site });
      reload();
    });
    controls.append(el('li', {}, [el('span', { className: 'site', textContent: site }), el('span', { className: 'muted', textContent: what }), btn]));
  }

  // picked elements
  const picked = $('picked');
  picked.textContent = '';
  const entries = Object.entries(settings.pickedHides || {});
  if (!entries.length) picked.append(el('li', { className: 'empty', textContent: t('options_none_yet') }));
  for (const [host, selectors] of entries) {
    for (const selector of selectors) {
      const btn = el('button', { title: t('options_show_again'), textContent: '×', ariaLabel: t('options_show_again') });
      btn.setAttribute('aria-label', t('options_show_again_on', selector, host));
      btn.addEventListener('click', async () => {
        await send({ type: 'options:removePicked', host, selector });
        reload();
      });
      picked.append(el('li', {}, [el('span', { className: 'site', textContent: host }), el('code', { textContent: selector, title: selector }), btn]));
    }
  }

  // shortcuts
  const sc = $('shortcuts');
  sc.textContent = '';
  if (!data.commands.length) sc.append(el('li', { className: 'empty', textContent: t('options_shortcuts_unavailable') }));
  for (const c of data.commands) {
    sc.append(el('li', {}, [el('span', { className: 'site', textContent: c.description }), c.shortcut ? el('kbd', { textContent: c.shortcut }) : el('span', { className: 'muted', textContent: t('options_no_shortcut') })]));
  }

  // custom filters
  if (!customDirty && document.activeElement !== $('custom')) $('custom').value = settings.customFilters || '';
  showErrors((state && state.customErrors) || []);

  // credits: every list with its authors and licence (CC BY-SA and CC BY ask for exactly this)
  const credits = $('credits');
  credits.textContent = '';
  for (const l of catalogue.lists.filter(x => x.source !== 'Bouclier')) {
    const name = /^https?:/.test(l.homepage || '') ? el('a', { href: l.homepage, textContent: l.source, target: '_blank', rel: 'noopener' }) : l.source;
    const licence = l.licenseUrl ? el('a', { href: l.licenseUrl, textContent: licenceName(l), target: '_blank', rel: 'noopener' }) : licenceName(l);
    // "{0} by {1} · {2}": the list's name, its authors as they name themselves, the licence
    credits.append(el('li', {}, l.authors ? withNodes(t('options_credit_by'), name, l.authors, licence) : [name, ' · ', licence]));
  }
}

function showErrors(errors) {
  const ul = $('custom-errors');
  ul.textContent = '';
  errors.forEach(e => ul.append(el('li', { textContent: e })));
}

$('save-custom').addEventListener('click', async () => {
  $('custom-status').textContent = t('options_saving');
  let res;
  try {
    res = await send({ type: 'options:saveCustom', text: $('custom').value });
  } catch (err) {
    res = { ok: false, error: String(err && err.message || err) };
  }
  showErrors((res && res.errors) || []);
  if (!res || res.ok === false) {
    $('custom-status').textContent = t('options_not_saved', (res && res.error) || t('options_unknown_error'));
    return;
  }
  customDirty = false;
  $('custom-status').textContent = res.errors && res.errors.length ? t('options_saved_skipped') : t('options_saved');
  reload();
});

$('reset-stats').addEventListener('click', async () => {
  if (!confirm(t('options_reset_stats_confirm'))) return;
  await send({ type: 'options:resetStats' });
  reload();
});

$('export').addEventListener('click', async () => {
  const res = await send({ type: 'options:export' });
  const text = JSON.stringify(res.data, null, 2);
  const blob = new Blob([text], { type: 'application/json' });
  const a = el('a', { href: URL.createObjectURL(blob), download: `bouclier-settings-${new Date().toISOString().slice(0, 10)}.json` });
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(a.href), 5000);
  $('backup-status').textContent = t('options_exported');
});

$('import-button').addEventListener('click', () => $('import-file').click());

$('import-file').addEventListener('change', async () => {
  const file = $('import-file').files[0];
  if (!file) return;
  if (file.size > 5 * 1024 * 1024) {
    $('backup-status').textContent = t('options_import_too_large');
    $('import-file').value = '';
    return;
  }
  try {
    const data = JSON.parse(await file.text());
    const res = await send({ type: 'options:import', data });
    if (res && res.imported && res.ok === false) {
      $('backup-status').textContent = t('options_import_partial', res.error);
    } else {
      const n = res && res.ok ? res.imported.length : 0;
      $('backup-status').textContent = res && res.ok ? (isOne(n) ? t('options_imported_one', n) : t('options_imported', n)) : (res && res.error) || t('options_import_failed');
    }
    customDirty = false;
  } catch {
    $('backup-status').textContent = t('options_import_unreadable');
  }
  $('import-file').value = '';
  reload();
});

$('open-welcome').addEventListener('click', () => {
  api.tabs.create({ url: api.runtime.getURL('pages/welcome.html') });
});

$('reset').addEventListener('click', async () => {
  if (!confirm(t('options_reset_confirm'))) return;
  await send({ type: 'options:reset' });
  reload();
});

load().catch(showLoadError);

// touch screens have no mouseleave: a tap outside the chart closes its tip
document.addEventListener('touchstart', e => {
  const tip = document.querySelector('.chart .tip');
  if (tip && !e.target.closest('.chart')) tip.style.display = 'none';
}, { passive: true });
