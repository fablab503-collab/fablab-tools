'use strict';
const api = globalThis.browser ?? globalThis.chrome;
const $ = id => document.getElementById(id);
const SVG = 'http://www.w3.org/2000/svg';
const P = globalThis.BOUCLIER_PLATFORM || { ios: false, device: 'this Mac', extensionSettings: 'Safari Settings → Extensions → Bouclier' };
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

function formatBytes(n) {
  if (n >= 1e9) return (n / 1e9).toFixed(1) + ' GB';
  if (n >= 1e6) return (n / 1e6).toFixed(n >= 1e8 ? 0 : 1) + ' MB';
  return Math.round(n / 1e3) + ' KB';
}

function send(message) {
  return api.runtime.sendMessage(message);
}

/* ------------------------------------------------------------- stats */

function renderStats(stats) {
  $('stat-today').textContent = stats.today.toLocaleString();
  $('stat-week').textContent = stats.week.toLocaleString();
  $('stat-all').textContent = stats.all.toLocaleString();
  $('stat-bytes').textContent = formatBytes(stats.bytesSaved);
  $('stat-bytes-note').textContent = `estimate, ~${Math.round(stats.bytesPerBlock / 1024)} KB per blocked request`;
  const extra = [];
  if (stats.totals.popups) extra.push(`${stats.totals.popups.toLocaleString()} pop-ups closed`);
  if (stats.totals.links) extra.push(`${stats.totals.links.toLocaleString()} links cleaned`);
  $('stats-since').textContent = `Counting since ${new Date(stats.since).toLocaleDateString()}` + (extra.length ? ` · ${extra.join(' · ')}` : '') + `. Statistics never leave ${P.device}.`;

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
      target.append(el('li', { className: 'empty', textContent: 'Nothing yet' }));
      return;
    }
    for (const [name, n] of rows) target.append(el('li', {}, [name, el('span', { className: 'n', textContent: n.toLocaleString() })]));
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
  for (let i = 0; i <= 2; i++) {
    const v = (niceMax / 2) * i;
    const y = pad.t + plotH - (v / niceMax) * plotH;
    root.append(svg('line', { x1: pad.l, x2: W - pad.r, y1: y, y2: y, class: 'grid', 'stroke-width': 1 }));
    const label = svg('text', { x: pad.l - 6, y: y + 3, 'text-anchor': 'end', class: 'axis' });
    label.textContent = v >= 1000 ? `${Math.round(v / 100) / 10}k` : String(v);
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
      label.textContent = i === days.length - 1 ? 'Today' : dt.toLocaleDateString([], { day: 'numeric', month: 'short' });
      root.append(label);
    }
    const hit = svg('rect', { x: pad.l + i * slot, y: pad.t, width: slot, height: plotH, class: 'bar-hit' });
    hit.addEventListener('mouseenter', () => {
      tip.textContent = '';
      const dt = new Date(d.day + 'T12:00:00');
      tip.append(el('div', {}, [dt.toLocaleDateString([], { weekday: 'long', day: 'numeric', month: 'long' })]));
      tip.append(el('div', {}, [el('b', { textContent: totals[i].toLocaleString() }), ' blocked']));
      if (series.length > 1) {
        for (const [k] of series) if (d.counts[k]) tip.append(el('div', {}, [`${stats.labels[k] || k}: ${d.counts[k].toLocaleString()}`]));
      }
      tip.style.left = `${x + barW / 2}px`;
      tip.style.top = `${pad.t + 10}px`;
      tip.style.display = 'block';
    });
    hit.addEventListener('mouseleave', () => { tip.style.display = 'none'; });
    root.append(hit);
  });
  box.append(root, tip);

  // table view: same numbers as text (relief for low-contrast colours and screen readers)
  const table = el('table');
  const head = el('tr', {}, [el('th', { textContent: 'Day' }), ...series.map(([k]) => el('th', { className: 'num', textContent: stats.labels[k] || k })), el('th', { className: 'num', textContent: 'Total' })]);
  table.append(el('thead', {}, [head]));
  const body = el('tbody');
  days.slice().reverse().forEach((d, idx) => {
    const i = days.length - 1 - idx;
    body.append(el('tr', {}, [el('td', { textContent: d.day }), ...series.map(([k]) => el('td', { className: 'num', textContent: (d.counts[k] || 0).toLocaleString() })), el('td', { className: 'num', textContent: totals[i].toLocaleString() })]));
  });
  table.append(body);
  const details = el('details', { className: 'table-view' }, [el('summary', { textContent: 'Show as table' }), el('div', { className: 'table-wrap' }, [table])]);
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
    load();
  });
  ul.append(opt('popupBlocker', 'Close pop-up ads', 'When a page opens an ad in a new tab or window, Bouclier closes it and shows a short notice.'));
  ul.append(opt('showBadge', 'Show the count on the toolbar icon', 'The number of requests blocked on the current page.'));
  ul.append(opt('youtube', 'Remove YouTube ads', 'Video ads, ad banners and the “ad blockers are not allowed” pop-up.'));
  ul.append(opt('youtubeHideShorts', 'Hide YouTube Shorts', 'Removes Shorts shelves, the Shorts tab and Shorts in search results.'));
}

/* ----------------------------------------------------------------- load */

async function load() {
  const data = await send({ type: 'options:state' });
  const { settings, catalogue, state, stats } = data;
  const built = new Date(catalogue.generated * 1000);
  const ageDays = Math.floor((Date.now() - built.getTime()) / 86400000);
  $('version-line').textContent = `Version ${data.version} · filter lists built ${built.toLocaleDateString()}`;
  const stale = $('stale-notice');
  stale.hidden = ageDays <= STALE_DAYS;
  stale.textContent = `The filter lists are ${ageDays} days old, and new ads slip through as lists age. Check the App Store for a Bouclier update.`;

  renderStats(stats);
  renderGeneral(settings);

  // budget meter
  const used = (state && state.webkitRules) || 0;
  $('meter-fill').style.width = Math.min(100, (used / 150000) * 100).toFixed(1) + '%';
  $('meter-text').textContent = `${used.toLocaleString()} of 150,000 Safari rules in use.`;

  // lists
  const tbody = $('lists');
  tbody.textContent = '';
  const active = new Set((state && state.enabledLists) || []);
  const waiting = new Set((state && state.overBudget) || []);
  for (const l of catalogue.lists) {
    const on = settings.enabledLists.includes(l.id);
    let status;
    if (settings.paused) status = el('span', { className: 'status-off', textContent: 'Paused' });
    else if (active.has(l.id)) status = el('span', { className: 'status-on', textContent: 'On' });
    else if (on && waiting.has(l.id)) status = el('span', { className: 'status-wait', textContent: 'Waiting (rule limit)' });
    else status = el('span', { className: 'status-off', textContent: 'Off' });
    const hiding = (l.genericSelectors || 0) + (l.dynamicSelectors || 0);
    const source = /^https?:/.test(l.homepage || '') && l.source !== 'Bouclier'
      ? el('a', { href: l.homepage, textContent: l.source, target: '_blank', rel: 'noopener' })
      : el('span', { textContent: l.source });
    tbody.append(el('tr', {}, [
      el('td', {}, [el('strong', { textContent: l.name }), el('div', { className: 'muted small', textContent: l.description })]),
      el('td', {}, [status]),
      el('td', { className: 'num', textContent: l.webkitRules.toLocaleString() }),
      el('td', { className: 'num', textContent: hiding.toLocaleString() + (l.specificSites ? ` + ${l.specificSites.toLocaleString()} sites` : '') }),
      el('td', {}, [source, el('div', { className: 'muted small', textContent: (l.version ? `v${l.version} · ` : '') + l.license })]),
    ]));
  }
  $('lists-date').textContent = 'Turn lists on or off from the toolbar button.';

  // paused sites
  const sites = $('sites');
  sites.textContent = '';
  if (!settings.pausedSites.length) sites.append(el('li', { className: 'empty', textContent: 'None' }));
  for (const site of settings.pausedSites) {
    const until = settings.sitePauseUntil && settings.sitePauseUntil[site];
    const label = until ? `${site} (until ${new Date(until).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })})` : site;
    const btn = el('button', { title: `Protect ${site} again`, textContent: '×' });
    btn.setAttribute('aria-label', `Protect ${site} again`);
    btn.addEventListener('click', async () => {
      await send({ type: 'options:removeSite', site });
      load();
    });
    sites.append(el('li', {}, [label, btn]));
  }

  // site controls
  const controls = $('controls');
  controls.textContent = '';
  const cEntries = Object.entries(settings.siteControls || {});
  if (!cEntries.length) controls.append(el('li', { className: 'empty', textContent: 'None' }));
  for (const [site, c] of cEntries) {
    const what = [c.fonts && 'web fonts blocked', c.scripts3p && 'scripts from other sites blocked', c.comments && 'comments hidden'].filter(Boolean).join(', ');
    const btn = el('button', { title: 'Remove these controls', textContent: '×' });
    btn.setAttribute('aria-label', `Remove site controls for ${site}`);
    btn.addEventListener('click', async () => {
      await send({ type: 'options:removeSiteControls', site });
      load();
    });
    controls.append(el('li', {}, [el('span', { className: 'site', textContent: site }), el('span', { className: 'muted', textContent: what }), btn]));
  }

  // picked elements
  const picked = $('picked');
  picked.textContent = '';
  const entries = Object.entries(settings.pickedHides || {});
  if (!entries.length) picked.append(el('li', { className: 'empty', textContent: 'None yet' }));
  for (const [host, selectors] of entries) {
    for (const selector of selectors) {
      const btn = el('button', { title: 'Show it again', textContent: '×' });
      btn.setAttribute('aria-label', `Show ${selector} on ${host} again`);
      btn.addEventListener('click', async () => {
        await send({ type: 'options:removePicked', host, selector });
        load();
      });
      picked.append(el('li', {}, [el('span', { className: 'site', textContent: host }), el('code', { textContent: selector, title: selector }), btn]));
    }
  }

  // shortcuts
  const sc = $('shortcuts');
  sc.textContent = '';
  if (!data.commands.length) sc.append(el('li', { className: 'empty', textContent: 'Not available in this browser' }));
  for (const c of data.commands) {
    sc.append(el('li', {}, [el('span', { className: 'site', textContent: c.description }), c.shortcut ? el('kbd', { textContent: c.shortcut }) : el('span', { className: 'muted', textContent: 'no shortcut set' })]));
  }

  // custom filters
  if (document.activeElement !== $('custom')) $('custom').value = settings.customFilters || '';
  showErrors((state && state.customErrors) || []);

  // credits: every list with its authors and licence (CC BY-SA and CC BY ask for exactly this)
  const credits = $('credits');
  credits.textContent = '';
  for (const l of catalogue.lists.filter(x => x.source !== 'Bouclier')) {
    const name = /^https?:/.test(l.homepage || '') ? el('a', { href: l.homepage, textContent: l.source, target: '_blank', rel: 'noopener' }) : l.source;
    const licence = l.licenseUrl ? el('a', { href: l.licenseUrl, textContent: l.license, target: '_blank', rel: 'noopener' }) : l.license;
    credits.append(el('li', {}, [name, l.authors ? ` by ${l.authors}` : '', ' · ', licence]));
  }
}

function showErrors(errors) {
  const ul = $('custom-errors');
  ul.textContent = '';
  errors.forEach(e => ul.append(el('li', { textContent: e })));
}

$('save-custom').addEventListener('click', async () => {
  $('custom-status').textContent = 'Saving… Safari recompiles the rules, this takes a few seconds.';
  const res = await send({ type: 'options:saveCustom', text: $('custom').value });
  showErrors(res.errors || []);
  $('custom-status').textContent = res.errors && res.errors.length ? 'Saved, but some lines were skipped:' : 'Saved.';
  load();
});

$('reset-stats').addEventListener('click', async () => {
  if (!confirm('Reset all statistics? This cannot be undone.')) return;
  await send({ type: 'options:resetStats' });
  load();
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
  $('backup-status').textContent = 'Saved to your Downloads folder.';
});

$('import-file').addEventListener('change', async () => {
  const file = $('import-file').files[0];
  if (!file) return;
  try {
    const data = JSON.parse(await file.text());
    const res = await send({ type: 'options:import', data });
    $('backup-status').textContent = res.ok ? `Imported ${res.imported.length} settings.` : res.error;
  } catch {
    $('backup-status').textContent = 'That file could not be read as a Bouclier backup.';
  }
  $('import-file').value = '';
  load();
});

$('open-welcome').addEventListener('click', () => {
  api.tabs.create({ url: api.runtime.getURL('pages/welcome.html') });
});

$('reset').addEventListener('click', async () => {
  if (!confirm('Reset Bouclier to its default settings? Paused sites, hidden elements, site controls and your filters will be removed.')) return;
  await send({ type: 'options:reset' });
  load();
});

load().catch(err => {
  document.querySelector('main').prepend(el('p', { className: 'errors', textContent: 'Could not load settings: ' + err.message }));
});
