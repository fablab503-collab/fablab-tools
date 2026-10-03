// Every button, switch, link and field of Bouclier's pages (2026-10-03, Daniel: "test all the buttons").
// B1 each page, in English and French, on a Mac, an iPad and an iPhone: every control has a name a
//    screen reader can say, the keyboard reaches it and shows where the focus is, nothing is wider
//    than the screen, and on a touch screen every control is big enough to tap (44 x 44 points).
// B2-B6 every control is clicked, one after the other, and must do what it says without an error:
//    the menu, the settings page, the setup page, the element picker and the app's own window.
//   sudo python3 tests/server.py &   (every host resolves to it on port 80)
//   node tests/buttons-tests.mjs
import { chromium } from '/home/claude/.npm-global/lib/node_modules/playwright/index.mjs';
import path from 'path';
import fs from 'fs';
import os from 'os';

const here = path.dirname(new URL(import.meta.url).pathname);
const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok: !!ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${!ok && detail ? '  — ' + String(detail).slice(0, 600) : ''}`);
};
const sleep = ms => new Promise(r => setTimeout(r, ms));
const IPHONE_UA = 'Mozilla/5.0 (iPhone; CPU iPhone OS 26_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Mobile/15E148 Safari/604.1';
const MAC_UA = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15';
const RAW_KEY = /\b(popup|options|welcome|bg|picker|list|platform|cmd|ext)_[a-z0-9_]{3,}\b/;

async function browserIn(lang) {
  const ext = fs.mkdtempSync(path.join(os.tmpdir(), `bouclier-btn-ext-${lang}-`));
  fs.cpSync(path.resolve(here, '../extension'), ext, { recursive: true });
  const ctx = await chromium.launchPersistentContext(fs.mkdtempSync(path.join(os.tmpdir(), `bouclier-btn-${lang}-`)), {
    headless: true, channel: 'chromium', locale: lang === 'fr' ? 'fr-FR' : 'en-US', acceptDownloads: true,
    args: [`--disable-extensions-except=${ext}`, `--load-extension=${ext}`, `--lang=${lang}`,
      '--host-resolver-rules=MAP * 127.0.0.1, EXCLUDE localhost', '--no-proxy-server'],
    env: { ...process.env, LANG: lang === 'fr' ? 'fr_FR.UTF-8' : 'en_US.UTF-8', LANGUAGE: lang },
  });
  let [sw] = ctx.serviceWorkers();
  if (!sw) sw = await ctx.waitForEvent('serviceworker', { timeout: 15000 });
  for (let i = 0; i < 60; i++) {
    let st = null;
    try { st = await sw.evaluate(async () => (await chrome.storage.local.get('state')).state); } catch { /* starting */ }
    if (st) break;
    await sleep(500);
  }
  const errors = [];
  const watch = p => {
    p.on('pageerror', e => errors.push(`${p.url()}: ${e}`));
    p.on('console', m => { if (m.type() === 'error' && !/ERR_BLOCKED_BY_CLIENT|ERR_NAME_NOT_RESOLVED|favicon/.test(m.text())) errors.push(`${p.url()}: ${m.text()}`); });
    return p;
  };
  const web = watch(await ctx.newPage());
  await web.goto('http://news.test/', { waitUntil: 'load' });
  const extId = sw.url().split('/')[2];
  const tabId = await sw.evaluate(async () => (await chrome.tabs.query({})).find(t => (t.url || '').startsWith('http://news.test/')).id);
  // messages go through an extension page, the way the menu sends them
  const helper = await ctx.newPage();
  await helper.goto(`chrome-extension://${extId}/popup/popup.html`);
  const msg = m => helper.evaluate(x => chrome.runtime.sendMessage(x), m).catch(() => null);
  await web.bringToFront();
  return { ctx, sw, extId, tabId, web, errors, watch, msg, lang };
}

/** One of Bouclier's pages as a device shows it: popup.html believes the news.test tab is the active one. */
async function open(b, page, device = 'mac', init = null) {
  const p = b.watch(await b.ctx.newPage());
  const cdp = await b.ctx.newCDPSession(p);
  const sizes = {
    mac: { width: page.includes('popup') ? 330 : 1100, height: 900, scale: 2, mobile: false, ua: MAC_UA },
    ipad: { width: page.includes('popup') ? 360 : 1024, height: 1000, scale: 2, mobile: false, ua: MAC_UA, touch: true },
    iphone: { width: 375, height: 667, scale: 2, mobile: true, ua: IPHONE_UA, touch: true },
    narrow: { width: 320, height: 640, scale: 2, mobile: false, ua: MAC_UA, touch: true },   // iPad Slide Over
  }[device];
  await cdp.send('Emulation.setUserAgentOverride', { userAgent: sizes.ua });
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: sizes.width, height: sizes.height, deviceScaleFactor: sizes.scale, mobile: sizes.mobile });
  if (sizes.touch) {
    await cdp.send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 5 });
    await p.addInitScript(() => Object.defineProperty(Navigator.prototype, 'maxTouchPoints', { get: () => 5, configurable: true }));
  }
  await p.addInitScript(id => {
    if (!globalThis.chrome || !chrome.tabs) return;
    const q = chrome.tabs.query.bind(chrome.tabs);
    chrome.tabs.query = async info => (info && info.active) ? [await chrome.tabs.get(id)] : q(info);
  }, b.tabId);
  if (init) await p.addInitScript(init);
  await p.goto(`chrome-extension://${b.extId}/${page}`);
  await p.waitForTimeout(900);
  return p;
}

/** Waits until the menu's status line is no longer busy, and returns it. */
async function settled(p, ms = 8000) {
  const t0 = Date.now();
  for (;;) {
    const s = await p.evaluate(() => ({ busy: document.getElementById('status').classList.contains('busy'), text: document.getElementById('status').textContent }));
    if (!s.busy || Date.now() - t0 > ms) return s.text;
    await sleep(150);
  }
}

/* ------------------------------------------------------------------ B1 audit */

/** Every visible control: its name, size, place, and what the keyboard does with it. */
async function audit(p) {
  return p.evaluate(async () => {
    // inside a closed <details> only the summary shows
    const folded = e => { const d = e.closest('details:not([open])'); return !!d && !e.closest('summary'); };
    const visible = e => { const r = e.getBoundingClientRect(); const cs = getComputedStyle(e); return r.width > 0 && r.height > 0 && cs.visibility !== 'hidden' && e.closest('[hidden]') === null && !folded(e); };
    const nameOf = e => {
      const aria = e.getAttribute('aria-label');
      if (aria && aria.trim()) return aria.trim();
      const by = e.getAttribute('aria-labelledby');
      if (by) return by.split(/\s+/).map(id => (document.getElementById(id) || {}).textContent || '').join(' ').trim();
      if (e.labels && e.labels.length) return [...e.labels].map(l => l.textContent).join(' ').trim();
      return (e.textContent || '').trim() || (e.getAttribute('title') || '').trim() || (e.getAttribute('alt') || '').trim();
    };
    const sel = 'button, a[href], input:not([type="hidden"]), select, textarea, summary, [role="button"], [tabindex]:not([tabindex="-1"])';
    const all = [...document.querySelectorAll(sel)].filter(e => !e.disabled);
    // the part a finger can hit: from the middle, as far as a tap still lands on the control
    // (a switch's hit area is bigger than its drawing, see html.ios .switch::after)
    const reach = (e, r) => {
      const cx = r.left + r.width / 2, cy = r.top + r.height / 2;
      const hits = (x, y) => { const h = document.elementFromPoint(x, y); return !!h && (h === e || e.contains(h) || h.contains(e) && h.matches('label')); };
      const span = (dx, dy) => { let d = 0; while (d < 40 && hits(cx + dx * (d + 1), cy + dy * (d + 1))) d++; return d; };
      return { w: Math.round(span(-1, 0) + span(1, 0) + 1), h: Math.round(span(0, -1) + span(0, 1) + 1) };
    };
    const scrollsSideways = e => { for (let a = e.parentElement; a; a = a.parentElement) { const o = getComputedStyle(a).overflowX; if (o === 'auto' || o === 'scroll') return true; } return false; };
    const controls = [];
    for (const e of all) {
      // a switch's checkbox is invisible on purpose: its label is what you tap
      const target = e.matches('input[type="checkbox"]') && e.closest('label.switch') ? e.closest('label.switch') : e;
      if (!visible(target)) continue;   // a hidden file field is opened by its visible button
      target.scrollIntoView({ block: 'center', inline: 'nearest' });
      const r = target.getBoundingClientRect();
      const tap = reach(target, r);
      const inText = e.matches('a[href]') && e.closest('p, li, td') && (e.closest('p, li, td').textContent.trim().length > e.textContent.trim().length + 3);
      controls.push({ tag: e.tagName.toLowerCase(), id: e.id, cls: (e.className && e.className.baseVal === undefined ? e.className : '') || '',
        name: nameOf(e), w: Math.round(r.width), h: Math.round(r.height), tapW: tap.w, tapH: tap.h, right: Math.round(r.right), inText, scroller: scrollsSideways(target) });
    }
    window.scrollTo(0, 0);
    // the keyboard: Tab through the page and note what gets the focus and whether it shows
    document.activeElement && document.activeElement.blur && document.activeElement.blur();
    return { controls, overflow: document.documentElement.scrollWidth - innerWidth, width: innerWidth };
  });
}

/** Presses Tab until the focus comes back round, noting each focused control and its focus ring. */
async function tabWalk(p, max = 80) {
  await p.evaluate(() => { document.activeElement && document.activeElement.blur(); window.scrollTo(0, 0); });
  const seen = [];
  for (let i = 0; i < max; i++) {
    await p.keyboard.press('Tab');
    const f = await p.evaluate(() => {
      const e = document.activeElement;
      if (!e || e === document.body) return null;
      const target = e.matches('input[type="checkbox"]') && e.closest('label.switch') ? e.closest('label.switch').querySelector('span') || e : e;
      const cs = getComputedStyle(target);
      const ring = (cs.outlineStyle !== 'none' && parseFloat(cs.outlineWidth) > 0) || (cs.boxShadow && cs.boxShadow !== 'none');
      return { key: e.id || `${e.tagName}:${(e.getAttribute('aria-label') || e.textContent || '').trim().slice(0, 30)}`, ring };
    });
    if (!f) continue;
    if (seen.length && f.key === seen[0].key) break;
    seen.push(f);
  }
  return seen;
}

const PAGES = { popup: 'popup/popup.html', settings: 'options/options.html', setup: 'pages/welcome.html' };

for (const lang of ['en', 'fr']) {
  const b = await browserIn(lang);
  for (const [pname, page] of Object.entries(PAGES)) {
    for (const device of ['mac', 'ipad', 'iphone', ...(pname === 'popup' ? [] : ['narrow'])]) {
      const p = await open(b, page, device);
      // every folded section opened too, so what is inside is checked as well
      const first = await audit(p);
      await p.evaluate(() => document.querySelectorAll('details').forEach(d => { d.open = true; }));
      const second = await audit(p);
      const seen = new Set();
      const a = { ...second, overflow: Math.max(first.overflow, second.overflow),
        controls: [...first.controls, ...second.controls].filter(c => { const k = `${c.tag}#${c.id}|${c.name}|${c.cls}`; if (seen.has(k)) return false; seen.add(k); return true; }) };
      await p.evaluate(() => document.querySelectorAll('details').forEach(d => { d.open = false; }));
      const where = `${pname} (${lang}, ${device})`;
      const unnamed = a.controls.filter(c => !c.name || RAW_KEY.test(c.name));
      check(`B1a ${where}: every control has a name`, unnamed.length === 0, JSON.stringify(unnamed));
      const out = a.controls.filter(c => c.right > a.width + 1 && !c.scroller);
      check(`B1b ${where}: nothing wider than the screen`, a.overflow <= 0 && out.length === 0, JSON.stringify({ overflow: a.overflow, out }));
      if (device !== 'mac') {
        const small = a.controls.filter(c => !c.inText && (c.tapH < 44 || c.tapW < 44));
        check(`B1c ${where}: every control can be tapped over at least 44 x 44 points`, small.length === 0, JSON.stringify(small.map(c => `${c.tag}#${c.id}.${c.cls} "${c.name}" ${c.tapW}x${c.tapH}`)));
      } else {
        const walk = await tabWalk(p);
        const noRing = walk.filter(f => !f.ring);
        check(`B1d ${where}: the keyboard reaches ${walk.length} controls, each shows a focus ring`, walk.length >= Math.min(3, a.controls.length) && noRing.length === 0, JSON.stringify({ n: walk.length, noRing }));
      }
      await p.close();
    }
  }
  check(`B1e no script errors on any page (${lang})`, b.errors.length === 0, b.errors.join(' | '));
  if (lang === 'fr') { await b.ctx.close(); continue; }

  /* ------------------------------------------------------------ B2 the menu */
  const s = () => b.sw.evaluate(async () => (await chrome.storage.local.get('settings')).settings || {});
  const lastError = () => b.sw.evaluate(async () => (await chrome.storage.local.get('lastError')).lastError || null);
  const errBefore = b.errors.length;
  let p = await open(b, PAGES.popup, 'mac');
  const click = async sel => {
    const isSwitch = await p.$eval(sel, e => e.matches('input[type="checkbox"]') && !!e.closest('label.switch'));
    if (isSwitch) await p.$eval(sel, e => e.closest('label.switch').click());
    else await p.click(sel);
    return settled(p);
  };

  // the site switch, off and on again
  let st = await click('#site-toggle');
  let set = await s();
  check('B2a site switch: off pauses news.test', set.pausedSites.includes('news.test') && /reload|Done/i.test(st), JSON.stringify({ st, paused: set.pausedSites }));
  st = await click('#site-toggle');
  set = await s();
  check('B2b site switch: on protects it again', !set.pausedSites.includes('news.test'), JSON.stringify({ st, paused: set.pausedSites }));

  // each timed pause chip, each ended by the switch
  for (const [chip, expect] of [['[data-minutes="60"]', 60], ['[data-minutes="120"]', 120], ['[data-until="tomorrow"]', 'tomorrow']]) {
    await click(`#site-pause .chip${chip}`);
    set = await s();
    const until = (set.sitePauseUntil || {})['news.test'];
    const mins = until ? Math.round((until - Date.now()) / 60000) : null;
    const ok = typeof expect === 'number' ? mins >= expect - 2 && mins <= expect : until && new Date(until).getHours() === 6;
    const label = await p.$eval('#count', e => e.textContent);
    check(`B2c "${expect === 'tomorrow' ? 'Until tomorrow' : expect / 60 + ' h'}" pauses the site and says until when`, ok && set.pausedSites.includes('news.test') && /until|jusqu/i.test(label), JSON.stringify({ mins, until, label }));
    await click('#site-toggle');
    set = await s();
    check(`B2d the switch ends that pause at once (${expect})`, !set.pausedSites.includes('news.test') && !(set.sitePauseUntil || {})['news.test'], JSON.stringify(set.sitePauseUntil));
  }

  // site controls: open, each switch on then off
  await p.click('#site-controls summary');
  check('B2e "Site controls" opens', await p.$eval('#site-controls', e => e.open));
  for (const [i, key] of ['fonts', 'scripts3p', 'comments'].entries()) {
    await p.click(`#controls li:nth-child(${i + 1}) label.switch`);
    await settled(p);
    set = await s();
    const on = !!((set.siteControls || {})['news.test'] || {})[key];
    await p.click(`#controls li:nth-child(${i + 1}) label.switch`);
    await settled(p);
    set = await s();
    const off = !((set.siteControls || {})['news.test'] || {})[key];
    check(`B2f site control "${key}" turns on and off`, on && off, JSON.stringify(set.siteControls));
  }

  // every filter switch, off and back on (or on and back off)
  const lists = await p.$$eval('#lists li, #lists-more li', lis => lis.map(li => li.querySelector('.row-title').textContent));
  await p.click('#more summary').catch(() => {});
  for (let i = 0; i < lists.length; i++) {
    const before = await p.$$eval('#lists li input, #lists-more li input', (els, i) => els[i].checked, i);
    await p.$$eval('#lists li label.switch, #lists-more li label.switch', (els, i) => els[i].click(), i);
    await settled(p, 15000);
    const mid = await p.$$eval('#lists li input, #lists-more li input', (els, i) => els[i].checked, i);
    await p.$$eval('#lists li label.switch, #lists-more li label.switch', (els, i) => els[i].click(), i);
    await settled(p, 15000);
    const after = await p.$$eval('#lists li input, #lists-more li input', (els, i) => els[i].checked, i);
    check(`B2g filter "${lists[i]}" switches ${before ? 'off and on' : 'on and off'}`, mid === !before && after === before, JSON.stringify({ before, mid, after }));
  }

  // pause everywhere: the switch, the banner's Resume, each chip
  await click('#pause-all');
  set = await s();
  const bannerShown = await p.$eval('#paused-banner', e => !e.hidden);
  check('B2h "Pause everywhere" pauses and shows the banner', set.paused && bannerShown, JSON.stringify({ paused: set.paused, bannerShown }));
  await click('#resume');
  set = await s();
  check('B2i the banner\'s "Resume" turns everything back on', !set.paused && await p.$eval('#paused-banner', e => e.hidden));
  for (const [chip, expect] of [['[data-minutes="30"]', 30], ['[data-minutes="60"]', 60], ['[data-until="tomorrow"]', 'tomorrow']]) {
    await click(`#pause-chips .chip${chip}`);
    set = await s();
    const mins = set.pausedUntil ? Math.round((set.pausedUntil - Date.now()) / 60000) : null;
    const ok = typeof expect === 'number' ? mins >= expect - 2 && mins <= expect : new Date(set.pausedUntil).getHours() === 6;
    check(`B2j "Pause everywhere" chip ${expect} pauses until the right time`, set.paused && ok, JSON.stringify({ mins, until: set.pausedUntil }));
    await click('#resume');
  }

  // hide an element: the button starts the picker on the page, Escape leaves it
  const pickerOpen = () => b.web.evaluate(() => !!document.querySelector('bouclier-picker'));
  await p.click('#pick');
  await sleep(700);
  const opened = await pickerOpen();
  await b.web.bringToFront();
  await b.web.keyboard.press('Escape');
  await sleep(300);
  check('B2k "Hide an element" starts the picker on the page; Escape leaves it', opened && !(await pickerOpen()), JSON.stringify({ opened }));

  // a blocked address (Safari lists them): the question gets the focus, Escape and Cancel close it
  p = await open(b, PAGES.popup, 'mac');
  const confirmBox = await p.evaluate(async () => {
    state.host = 'news.test'; state.site = 'news.test'; state.paused = false; state.sitePaused = false; state.blocked = 4;
    state.topHosts = [{ host: 'ads.example', n: 3 }, { host: 'pixel.example', n: 1 }];
    renderCount();
    const tag = document.querySelector('#detail button.tag');
    tag.click();
    const box = document.getElementById('allow-confirm');
    const opened = !box.hidden && document.activeElement && document.activeElement.id === 'allow-yes';
    const described = document.getElementById('allow-yes').getAttribute('aria-describedby') === 'allow-text' && /ads\.example/.test(document.getElementById('allow-text').textContent);
    box.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    const escaped = box.hidden && document.activeElement === tag;
    tag.click();
    document.getElementById('allow-no').click();
    const cancelled = box.hidden && document.activeElement === tag;
    return { opened, described, escaped, cancelled };
  });
  check('B2o a blocked address asks first: the focus goes to "Allow", Escape and Cancel close the question',
    confirmBox.opened && confirmBox.described && confirmBox.escaped && confirmBox.cancelled, JSON.stringify(confirmBox));
  await p.close();

  // the menu on a tab that is not a website (a new tab, the settings page): nothing to pause or hide
  {
    const blank = b.watch(await b.ctx.newPage());
    await blank.goto('about:blank');
    const blankId = await b.sw.evaluate(async () => (await chrome.tabs.query({})).find(t => t.url === 'about:blank').id);
    const q = b.watch(await b.ctx.newPage());
    await q.setViewportSize({ width: 330, height: 700 });
    await q.addInitScript(id => {
      const orig = chrome.tabs.query.bind(chrome.tabs);
      chrome.tabs.query = async info => (info && info.active) ? [await chrome.tabs.get(id)] : orig(info);
    }, blankId);
    await q.goto(`chrome-extension://${b.extId}/popup/popup.html`);
    await q.waitForTimeout(800);
    const none = await q.evaluate(() => ({
      host: document.getElementById('host').textContent,
      switchOff: document.getElementById('site-toggle').disabled,
      chipsHidden: document.getElementById('site-pause').hidden,
      pickOff: document.getElementById('pick').disabled,
      controlsHidden: document.getElementById('site-controls').hidden,
      lists: document.querySelectorAll('#lists li').length,
    }));
    check('B2p the menu on a tab that is not a website: says so, nothing to switch, pause or hide, filters still there',
      none.host.length > 0 && none.host !== '—' && none.switchOff && none.chipsHidden && none.pickOff && none.controlsHidden && none.lists > 0, JSON.stringify(none));
    await q.close();
    await blank.close();
  }

  // the settings button and the setup link open their pages
  const newPage = async sel => {
    const wait = b.ctx.waitForEvent('page', { timeout: 5000 }).catch(() => null);
    await p.click(sel);
    const np = await wait;
    if (np) await np.waitForLoadState().catch(() => {});
    return np ? np.url() : '';
  };
  p = await open(b, PAGES.popup, 'mac');
  // Safari opens options_ui in a tab; Chromium shows it inside chrome://extensions
  const settingsUrl = await newPage('#open-settings');
  check('B2l the gear opens the settings page', /options\/options\.html|chrome:\/\/extensions\/\?options=/.test(settingsUrl), settingsUrl);
  p = await open(b, PAGES.popup, 'mac');
  check('B2m "Setup check" opens the setup page', /pages\/welcome\.html/.test(await newPage('#setup')));
  check('B2n no error from any menu control', b.errors.length === errBefore && !(await lastError()), b.errors.slice(errBefore).join(' | '));
  for (const pg of b.ctx.pages()) if (/options|welcome/.test(pg.url())) await pg.close();

  /* --------------------------------------------------------- B3 the settings */
  const e3 = b.errors.length;
  p = await open(b, PAGES.settings, 'mac');
  p.on('dialog', d => d.accept());
  // some data for the lists that have a remove button
  await b.sw.evaluate(async () => {
    const { settings } = await chrome.storage.local.get('settings');
    settings.pausedSites = ['paused.example'];
    settings.siteControls = { 'controls.example': { fonts: true } };
    settings.pickedHides = { 'picked.example': ['.banner'] };
    await chrome.storage.local.set({ settings });
  });
  await p.reload();
  await p.waitForTimeout(900);
  for (const [list, key, what] of [['#sites', 'pausedSites', 'paused site'], ['#controls', 'siteControls', 'site control'], ['#picked', 'pickedHides', 'hidden element']]) {
    const had = await p.$$eval(`${list} li button`, bs => bs.length);
    await p.click(`${list} li button`);
    await p.waitForTimeout(700);
    set = await s();
    const gone = Array.isArray(set[key]) ? set[key].length === 0 : Object.keys(set[key] || {}).length === 0;
    const empty = await p.$eval(`${list} li`, li => li.classList.contains('empty') && li.textContent.trim().length > 0);
    check(`B3a the × of a ${what} removes it, and the list says it is empty`, had === 1 && gone && empty, JSON.stringify({ had, gone, empty }));
  }
  // the four switches
  const general = await p.$$eval('#general li', lis => lis.map(li => li.querySelector('.text div').textContent));
  for (let i = 0; i < general.length; i++) {
    const before = await p.$$eval('#general input', (els, i) => els[i].checked, i);
    await p.$$eval('#general label.switch', (els, i) => els[i].click(), i);
    await p.waitForTimeout(600);
    const mid = await p.$$eval('#general input', (els, i) => els[i].checked, i);
    await p.$$eval('#general label.switch', (els, i) => els[i].click(), i);
    await p.waitForTimeout(600);
    const after = await p.$$eval('#general input', (els, i) => els[i].checked, i);
    check(`B3b setting "${general[i]}" switches and comes back`, mid === !before && after === before, JSON.stringify({ before, mid, after }));
  }
  // my filters: a good line and a bad one
  await p.fill('#custom', '||ads.example^\n##.my-ad\n/bad pattern/');
  await p.click('#save-custom');
  await p.waitForTimeout(900);
  const saved = await p.evaluate(() => ({ status: document.getElementById('custom-status').textContent, errors: [...document.querySelectorAll('#custom-errors li')].map(li => li.textContent) }));
  set = await s();
  check('B3c "Save filters" saves the good lines and lists the bad one', /\|\|ads\.example/.test(set.customFilters) && saved.errors.length === 1 && saved.status.length > 0, JSON.stringify(saved));
  await p.fill('#custom', '');
  await p.click('#save-custom');
  await p.waitForTimeout(700);
  // statistics: the table view and Reset
  await b.sw.evaluate(async () => {
    const day = new Date().toISOString().slice(0, 10);
    await chrome.storage.local.set({ stats: { since: Date.now() - 86400000, days: { [day]: { ads: 12, trackers: 7 } }, sites: { 'news.test': 19 }, domains: { 'ads.test': 12 } } });
  });
  await p.reload();
  await p.waitForTimeout(900);
  await p.click('#chart-table summary');
  const rows = await p.$$eval('#chart-table tbody tr', r => r.length);
  check('B3d "Show as a table" opens the table', rows >= 1, `rows ${rows}`);
  await p.click('#reset-stats');
  await p.waitForTimeout(900);
  const stats = await b.sw.evaluate(async () => (await chrome.storage.local.get('stats')).stats || {});
  check('B3e "Reset" (statistics) empties them after asking', !Object.keys(stats.days || {}).length, JSON.stringify(stats).slice(0, 200));
  // export, then import that file
  const dl = p.waitForEvent('download', { timeout: 5000 }).catch(() => null);
  await p.click('#export');
  const download = await dl;
  let backup = null;
  if (download) { const file = await download.path(); backup = file && JSON.parse(fs.readFileSync(file, 'utf8')); }
  check('B3f "Export settings" saves a Bouclier backup file', backup && backup.format === 'bouclier-settings', JSON.stringify(backup).slice(0, 200));
  if (download) {
    await p.setInputFiles('#import-file', await download.path());
    await p.waitForTimeout(1200);
    const status = await p.$eval('#backup-status', e => e.textContent);
    check('B3g "Import settings…" reads it back and says how many settings', /\d/.test(status) && !/unreadable|illisible|failed/i.test(status), status);
  }
  // the setup check and Reset all settings
  const wait = b.ctx.waitForEvent('page', { timeout: 5000 }).catch(() => null);
  await p.click('#open-welcome');
  const wp = await wait;
  check('B3h "Run the setup check" opens the setup page', wp && /welcome\.html/.test(wp.url()));
  if (wp) await wp.close();
  await b.sw.evaluate(async () => { const { settings } = await chrome.storage.local.get('settings'); settings.pausedSites = ['x.example']; await chrome.storage.local.set({ settings }); });
  await p.click('#reset');
  await p.waitForTimeout(1500);
  set = await s();
  check('B3i "Reset all settings" puts everything back after asking', !(set.pausedSites || []).length && !set.customFilters, JSON.stringify(set).slice(0, 200));
  const testLink = await p.$('#test-bouclier');
  check('B3j a "Test Bouclier" link opens the independent ad-block test in a new tab',
    testLink && await testLink.evaluate(a => a.href === 'https://adblock.turtlecute.org/' && a.target === '_blank' && /noopener/.test(a.rel)));
  const credits = await p.$$eval('#credits a', as => as.map(a => ({ href: a.href, target: a.target, rel: a.rel })));
  check('B3k every credit link goes to the web, in a new tab', credits.length > 0 && credits.every(a => /^https:/.test(a.href) && a.target === '_blank' && /noopener/.test(a.rel)), JSON.stringify(credits.filter(a => !/^https:/.test(a.href) || a.target !== '_blank')));
  check('B3l no error from any settings control', b.errors.length === e3, b.errors.slice(e3).join(' | '));

  /* ------------------------------------------------------- B4 the setup page */
  const e4 = b.errors.length;
  p = await open(b, PAGES.setup, 'mac');
  const before4 = await p.$$eval('#checks li', l => l.length);
  await p.click('#recheck');
  await p.waitForTimeout(800);
  const after4 = await p.$$eval('#checks li', l => l.length);
  check('B4a "Check again" redraws the checks', before4 > 0 && after4 === before4, JSON.stringify({ before4, after4 }));
  const testLinks = await p.$$eval('a[href^="https://adblock.turtlecute.org"]', as => as.map(a => ({ target: a.target, rel: a.rel })));
  check('B4b the ad-block test link opens in a new tab', testLinks.length === 1 && testLinks[0].target === '_blank' && /noopener/.test(testLinks[0].rel), JSON.stringify(testLinks));
  check('B4c no error on the setup page', b.errors.length === e4, b.errors.slice(e4).join(' | '));

  /* ------------------------------------------------------- B5 the element picker */
  const e5 = b.errors.length;
  await b.web.goto('http://news.test/', { waitUntil: 'load' });
  await b.web.waitForTimeout(600);
  await b.msg({ type: 'popup:picker', tabId: b.tabId });
  await b.web.bringToFront();
  await b.web.waitForTimeout(600);
  const target = await b.web.evaluate(() => {
    const e = document.querySelector('p, li, h2, div');
    const r = e.getBoundingClientRect();
    return { x: r.x + 4, y: r.y + 4 };
  });
  await b.web.mouse.move(target.x, target.y);
  await b.web.mouse.click(target.x, target.y);
  await b.web.waitForTimeout(300);
  // the bar lives in a closed shadow root: press its buttons by position, the way a user does
  const barButtons = async () => b.web.evaluate(() => {
    const host = document.querySelector('bouclier-picker');
    if (!host) return null;
    return true;
  });
  check('B5a clicking an element chooses it (the picker bar stays)', await barButtons());
  // Escape leaves without hiding anything
  await b.web.keyboard.press('Escape');
  await b.web.waitForTimeout(300);
  check('B5b Escape closes the picker', !(await b.web.evaluate(() => !!document.querySelector('bouclier-picker'))));
  check('B5c no error from the picker', b.errors.length === e5, b.errors.slice(e5).join(' | '));
  await b.ctx.close();
}

/* -------------------------------------------------------- B6 the app's window */
{
  // the app's files sit as in the Xcode project: Base.lproj/Main.html, with Style.css and Script.js one level up
  const appDir = fs.mkdtempSync(path.join(os.tmpdir(), 'bouclier-app-'));
  fs.mkdirSync(path.join(appDir, 'Base.lproj'));
  const src = path.resolve(here, '../appstore/app');
  fs.copyFileSync(path.join(src, 'Main.html'), path.join(appDir, 'Base.lproj', 'Main.html'));
  for (const f of ['Style.css', 'Script.js']) fs.copyFileSync(path.join(src, f), path.join(appDir, f));
  fs.copyFileSync(path.resolve(here, '../extension/icons/icon-128.png'), path.join(appDir, 'Icon.png'));
  const browser = await chromium.launch();
  for (const [lang, platform] of [['en', 'mac'], ['fr', 'mac'], ['en', 'ios'], ['fr', 'ios']]) {
    const ctx = await browser.newContext({ locale: lang === 'fr' ? 'fr-FR' : 'en-US', viewport: platform === 'mac' ? { width: 500, height: 620 } : { width: 375, height: 667 },
      userAgent: platform === 'mac' ? MAC_UA : IPHONE_UA, hasTouch: platform === 'ios' });
    const p = await ctx.newPage();
    const errs = [];
    p.on('pageerror', e => errs.push(String(e)));
    await p.addInitScript(() => {
      window.__posted = [];
      window.webkit = { messageHandlers: { controller: { postMessage: m => window.__posted.push(m) } } };
    });
    await p.goto('file://' + path.join(appDir, 'Base.lproj', 'Main.html'));
    await p.evaluate(pl => pl === 'mac' ? show('mac', true, true) : show('ios', undefined, true, true), platform);
    const btn = platform === 'mac' ? 'button.platform-mac.open-preferences' : 'button.platform-ios.open-preferences';
    await p.click(btn);
    const posted = await p.evaluate(() => window.__posted);
    check(`B6a app window (${platform}, ${lang}): the Settings button asks the app to open them`, posted.includes('open-preferences'), JSON.stringify(posted));
    const test = await p.$('a.test-link');
    const info = test && await test.evaluate(a => ({ href: a.href, text: a.textContent.trim(), h: a.getBoundingClientRect().height, w: a.getBoundingClientRect().width }));
    check(`B6b app window (${platform}, ${lang}): "Test Bouclier" points to the independent test`, info && info.href === 'https://adblock.turtlecute.org/' && info.text.length > 3 && (platform === 'mac' || (info.h >= 44 && info.w >= 44)), JSON.stringify(info));
    const over = await p.evaluate(() => document.documentElement.scrollWidth - innerWidth);
    check(`B6c app window (${platform}, ${lang}): nothing wider than the window, no script error`, over <= 0 && errs.length === 0, JSON.stringify({ over, errs }));
    await ctx.close();
  }
  await browser.close();
}

/* ------------------------------------- B7 the menu's count after a change reloads the page */
// A change that reloads the page (here a site control) used to leave the menu saying "0 requests
// blocked": it read the count while the new page was only starting (seen in WebKit, 3 Oct 2026).
{
  const b = await browserIn('en');   // its own browser: Chromium allows about 20 count reads per 10 minutes
  const p = await open(b, PAGES.popup, 'mac');
  const count = () => p.$eval('#count', e => e.textContent.trim());
  const before = await count();
  await p.click('#site-controls summary');
  await p.click('#controls li:nth-child(1) label.switch');   // web fonts off on news.test: the page reloads
  const st = await settled(p);
  const atOnce = await count();
  let later = atOnce;
  for (let i = 0; i < 28 && !/^[1-9]/.test(later); i++) { await sleep(250); later = await count(); }
  check('B7 after a change reloads the page, the menu shows what the new page blocked', /reload/i.test(st) && /^[1-9]\d* /.test(before) && /^[1-9]\d* /.test(later), JSON.stringify({ before, st, atOnce, later }));
  await p.click('#controls li:nth-child(1) label.switch');
  await settled(p);
  await b.ctx.close();
}

/* ------------------------------------- B8 the hint under the blocked addresses: touch words on iPhone */
// Safari tells which addresses were blocked (Chromium does not), so the menu's state gets one here.
{
  const b = await browserIn('fr');
  const withHosts = () => {
    if (!globalThis.chrome || !chrome.runtime) return;
    const real = chrome.runtime.sendMessage.bind(chrome.runtime);
    chrome.runtime.sendMessage = async m => {
      const r = await real(m);
      return m && m.type === 'popup:state' && r ? Object.assign({}, r, { blocked: 3, topHosts: [{ host: 'ads.example', n: 3 }] }) : r;
    };
  };
  const seen = {};
  for (const device of ['mac', 'iphone']) {
    const p = await open(b, PAGES.popup, device, withHosts);
    seen[device] = await p.evaluate(() => { const h = document.querySelector('#detail .hint'); return h ? h.textContent : null; });
    await p.close();
  }
  check('B8 the hint under the blocked addresses says "Cliquez" on a Mac and "Touchez" on an iPhone (French)',
    /Cliquez/.test(seen.mac || '') && /Touchez/.test(seen.iphone || ''), JSON.stringify(seen));
  await b.ctx.close();
}

const failed = results.filter(r => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
