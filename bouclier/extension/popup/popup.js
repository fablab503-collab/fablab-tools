'use strict';
const api = globalThis.browser ?? globalThis.chrome;
const $ = id => document.getElementById(id);
const P = globalThis.BOUCLIER_PLATFORM || { ios: false, device: 'this Mac', extensionSettings: 'Safari Settings → Extensions → Bouclier' };

let tab = null;
let state = null;

function send(message) {
  return api.runtime.sendMessage(message);
}

function setStatus(text, busy = false) {
  const el = $('status');
  el.textContent = text || '';
  el.classList.toggle('busy', !!busy);
}

function timeLabel(ts) {
  const d = new Date(ts);
  const sameDay = d.toDateString() === new Date().toDateString();
  const time = d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  return sameDay ? time : `${d.toLocaleDateString([], { weekday: 'short' })} ${time}`;
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
    const res = await send(message);
    if (res && res.ok === false) throw new Error(res.error || 'not possible here');
    if (reload && tab) await api.tabs.reload(tab.id);
    await refresh();
    setStatus(reload ? 'Done. Page reloaded.' : 'Done.');
  } catch (err) {
    setStatus('Something went wrong: ' + (err && err.message || err));
  } finally {
    document.body.removeAttribute('aria-busy');
  }
}

const SHORT = {
  ads: 'Ad networks, banners and pop-ups',
  privacy: 'Analytics and tracking scripts',
  french: 'Liste FR, for French sites',
  safety: 'Known malware sites',
  urlclean: 'Strips utm_, fbclid, gclid… from links',
  antiadblock: '“Turn off your ad blocker” walls',
  cookies: 'Consent pop-ups (can break some sites)',
  social: 'Like and share buttons, social embeds',
  annoyances: 'Newsletter and “open in app” pop-ups',
};

function listFor(l) {
  return {
    name: l.name,
    description: SHORT[l.id] || l.description,
    enabled: l.enabled,
    disabled: state.paused,
    note: l.enabled && !l.active && !state.paused ? 'Waiting for room under Safari\'s rule limit' : '',
    onToggle: () => run(`${l.enabled ? 'Turning off' : 'Turning on'} ${l.name}… Safari recompiles the rules, this takes a few seconds.`,
      { type: 'popup:toggleList', id: l.id }),
  };
}

function optionItem(key, name, description) {
  return {
    name,
    description,
    enabled: !!state[key],
    disabled: state.paused,
    onToggle: input => run(`${input.checked ? 'Turning on' : 'Turning off'} ${name}…`, { type: 'popup:setOption', key, value: input.checked }),
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
      count.textContent = state.pausedUntil ? `Paused everywhere until ${timeLabel(state.pausedUntil)}.` : 'Paused everywhere.';
    } else {
      count.textContent = state.sitePausedUntil
        ? `Paused on this site until ${timeLabel(state.sitePausedUntil)}.`
        : 'Paused on this site. Ads and trackers load normally.';
    }
    return;
  }
  if (typeof state.blocked !== 'number') {
    count.textContent = 'Protection is on.';
    return;
  }
  const strong = document.createElement('strong');
  strong.textContent = state.blocked.toLocaleString();
  count.append(strong, document.createTextNode(state.blocked === 1 ? ' request blocked on this page' : ' requests blocked on this page'));

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
      tag.title = `Something broken? Allow ${label} on ${state.site}`;
      tag.addEventListener('click', () => askAllow(label));
    }
    detail.append(tag);
  }
  if (hostTags) {
    const hint = document.createElement('div');
    hint.className = 'hint';
    hint.textContent = 'Page broken? Click an address to allow it on this site.';
    detail.append(hint);
  }
  const links = state.breakdown && state.breakdown.links;
  if (links) {
    const note = document.createElement('div');
    note.textContent = links === 1 ? 'Tracking tags removed from this page’s address.' : `Tracking tags removed from ${links} addresses.`;
    detail.append(note);
  }
}

function askAllow(host) {
  const box = $('allow-confirm');
  $('allow-text').textContent = `Allow ${host} on ${state.site}? Bouclier keeps blocking it everywhere else.`;
  box.hidden = false;
  $('allow-yes').onclick = () => {
    box.hidden = true;
    run(`Allowing ${host} on ${state.site}…`, { type: 'popup:allowHost', host, site: state.site }, { reload: true });
  };
  $('allow-no').onclick = () => { box.hidden = true; };
}

function renderControls() {
  const ul = $('controls');
  ul.textContent = '';
  const c = state.siteControls || {};
  const items = [
    ['fonts', 'Block web fonts', 'Faster pages; some icons may turn into squares'],
    ['scripts3p', 'Block scripts from other sites', 'Strict: stops most widgets and players too'],
    ['comments', 'Hide comments', 'Hides comment sections on this site'],
  ];
  for (const [key, name, description] of items) {
    ul.append(listRow({
      name,
      description,
      enabled: !!c[key],
      disabled: state.paused || state.sitePaused,
      onToggle: input => run(`${input.checked ? 'Turning on' : 'Turning off'} “${name}” for ${state.site}…`,
        { type: 'popup:siteControl', host: state.host, key, value: input.checked }, { reload: true }),
    }));
  }
  const active = items.filter(([key]) => c[key]).length;
  $('site-controls').querySelector('summary').textContent = active ? `Site controls (${active} on)` : 'Site controls';
}

function render() {
  const web = !!state.host;
  const todayBits = [];
  if (typeof state.today === 'number') todayBits.push(`${state.today.toLocaleString()} blocked today`);
  if (state.todayPopups) todayBits.push(`${state.todayPopups} pop-up${state.todayPopups === 1 ? '' : 's'} closed`);
  $('today').textContent = todayBits.join(' · ') || `Version ${state.version}`;

  $('host').textContent = web ? state.site : 'No website in this tab';
  const siteOn = web && !state.sitePaused && !state.paused;
  $('site-toggle').checked = siteOn;
  $('site-toggle').disabled = !web || state.paused;
  $('site-card').classList.toggle('off', !siteOn);
  renderCount();

  $('pick').disabled = !web || !siteOn || !state.hostAccess;
  $('pause-hour').disabled = !web || !siteOn;
  $('pause-hour').hidden = !web || !siteOn;

  $('hidden-note').hidden = !(web && state.hiddenCount);
  $('hidden-text').textContent = state.hiddenCount === 1 ? '1 element hidden by you' : `${state.hiddenCount} elements hidden by you`;

  $('site-controls').hidden = !web;
  if (web) renderControls();

  $('access-banner').hidden = state.hostAccess;
  $('paused-banner').hidden = !state.paused;
  $('paused-text').textContent = state.pausedUntil
    ? `Protection is paused everywhere until ${timeLabel(state.pausedUntil)}.`
    : 'Protection is paused everywhere.';
  $('pause-all').checked = state.paused;
  $('pause-chips').hidden = state.paused;
  $('pause-desc').textContent = state.paused && state.pausedUntil
    ? `Back on automatically at ${timeLabel(state.pausedUntil)}.`
    : 'Turns off all blocking until you switch it back on.';

  const over = state.overBudget || [];
  const budget = $('budget-banner');
  if (over.length) {
    const names = state.lists.filter(l => over.includes(l.id)).map(l => l.name).join(', ');
    budget.textContent = `Safari's rule limit is reached, so these filters are waiting: ${names}. Turn another filter off to make room.`;
    budget.hidden = false;
  } else {
    budget.hidden = true;
  }
  const err = $('error-banner');
  if (state.lastError && Date.now() - state.lastError.at < 10 * 60 * 1000) {
    err.textContent = 'Last change failed: ' + state.lastError.message;
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
    name: 'YouTube ads',
    description: 'Video ads and ad banners',
    enabled: state.youtube,
    disabled: state.paused,
    onToggle: () => run(state.youtube ? 'Turning YouTube ad removal off…' : 'Turning YouTube ad removal on…', { type: 'popup:toggleYoutube' }),
  }));
  main.append(listRow(optionItem('popupBlocker', 'Pop-up ads', 'Closes ad tabs that pages open')));
  addList(main, 'safety');
  addList(main, 'urlclean');
  let extraOn = false;
  for (const l of state.lists) {
    if (seen.has(l.id)) continue;
    if (l.enabled) extraOn = true;
    more.append(listRow(listFor(l)));
  }
  more.append(listRow(optionItem('youtubeHideShorts', 'Hide YouTube Shorts', 'Shorts shelves, tabs and results')));
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
    run(turningOff ? `Pausing on ${state.site}…` : `Protecting ${state.site} again…`,
      { type: 'popup:toggleSite', host: state.host }, { reload: true });
  });
  $('pause-hour').addEventListener('click', () => {
    run(`Pausing on ${state.site} for an hour…`, { type: 'popup:pauseSiteFor', host: state.host, minutes: 60 }, { reload: true });
  });
  $('pause-all').addEventListener('change', () => {
    run(state.paused ? 'Resuming protection…' : 'Pausing everywhere…', { type: 'popup:togglePause' }, { reload: true });
  });
  $('resume').addEventListener('click', () => {
    run('Resuming protection…', { type: 'popup:togglePause' }, { reload: true });
  });
  for (const chip of document.querySelectorAll('#pause-chips .chip')) {
    chip.addEventListener('click', () => {
      const msg = chip.dataset.until
        ? { type: 'popup:pauseAllFor', until: chip.dataset.until }
        : { type: 'popup:pauseAllFor', minutes: Number(chip.dataset.minutes) };
      run(`Pausing everywhere ${chip.textContent.toLowerCase()}…`, msg, { reload: true });
    });
  }
  $('unhide').addEventListener('click', () => {
    run(`Showing hidden elements on ${state.site} again…`, { type: 'popup:unhideSite', host: state.host }, { reload: true });
  });
  $('pick').addEventListener('click', async () => {
    try {
      const res = await send({ type: 'popup:picker', tabId: tab.id });
      if (res && res.ok === false) throw new Error('picker');
      window.close();
    } catch {
      setStatus('Could not start the picker on this page.');
    }
  });
  $('open-settings').addEventListener('click', () => {
    api.runtime.openOptionsPage();
    window.close();
  });
  $('setup').addEventListener('click', async () => {
    await api.tabs.create({ url: api.runtime.getURL('pages/welcome.html') });
    window.close();
  });
  $('grant').addEventListener('click', async () => {
    try {
      const granted = await api.permissions.request({ origins: ['<all_urls>'] });
      if (granted) {
        await refresh();
        setStatus('Thanks! Reload pages to apply.');
      }
    } catch {
      setStatus(`Open ${P.extensionSettings} and allow it on all websites.`);
    }
  });

  if (state.applying) setStatus('Applying your last change…', true);
}

main().catch(err => setStatus('Could not load: ' + (err && err.message || err)));
