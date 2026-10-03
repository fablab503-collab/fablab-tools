// QA regression tests (2026-09-28): invalid input, races and stress, older-Safari fallbacks,
// small iPhone screens and accessibility. Runs next to run-tests.mjs, with its own profile:
//   sudo python3 tests/server.py &   (every host resolves to it on port 80)
//   node tests/qa-tests.mjs
import { chromium } from '/home/claude/.npm-global/lib/node_modules/playwright/index.mjs';
import path from 'path';
import fs from 'fs';
import os from 'os';

const here = path.dirname(new URL(import.meta.url).pathname);
const ext = fs.mkdtempSync(path.join(os.tmpdir(), 'bouclier-qa-ext-'));
fs.cpSync(path.resolve(here, '../extension'), ext, { recursive: true });
const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'bouclier-qa-profile-'));
const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok: !!ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${!ok && detail ? '  — ' + String(detail).slice(0, 400) : ''}`);
};

const ctx = await chromium.launchPersistentContext(profile, {
  headless: true,
  channel: 'chromium',
  args: [`--disable-extensions-except=${ext}`, `--load-extension=${ext}`,
    '--host-resolver-rules=MAP * 127.0.0.1, EXCLUDE localhost', '--no-proxy-server'],
});
let [sw] = ctx.serviceWorkers();
if (!sw) sw = await ctx.waitForEvent('serviceworker', { timeout: 15000 });
const extId = sw.url().split('/')[2];
for (let i = 0; i < 60; i++) {
  let st = null;
  try { st = await sw.evaluate(async () => (await chrome.storage.local.get('state')).state); } catch { /* starting */ }
  if (st) break;
  await new Promise(r => setTimeout(r, 500));
}
const pageErrors = [];
const watch = p => {
  p.on('pageerror', e => pageErrors.push(`${p.url()}: ${e}`));
  p.on('console', m => { if (m.type() === 'error' && !m.text().includes('ERR_BLOCKED_BY_CLIENT')) pageErrors.push(`${p.url()}: ${m.text()}`); });
  return p;
};
const ui = watch(await ctx.newPage());
await ui.goto(`chrome-extension://${extId}/popup/popup.html`);
const msg = m => ui.evaluate(async x => chrome.runtime.sendMessage(x), m);
const settings = () => sw.evaluate(async () => (await chrome.storage.local.get('settings')).settings || {});
const dynamicRules = () => sw.evaluate(async () => chrome.declarativeNetRequest.getDynamicRules());
const log = async () => (await (await fetch('http://127.0.0.1/__log')).json());
const reset = () => fetch('http://127.0.0.1/__reset');
const web = watch(await ctx.newPage());
const disp = async sel => web.$eval(sel, e => getComputedStyle(e).display);
const resetAll = async () => { await msg({ type: 'options:reset' }); };

/* ---------------------------------------------------------------- invalid input */

// Q1 a backup listing a filter list twice used to remove every content script
{
  const res = await msg({ type: 'options:import', data: { format: 'bouclier-settings', version: 1, settings: { enabledLists: ['ads', 'ads', 'privacy', 'privacy', 'urlclean'] } } });
  const scripts = await sw.evaluate(async () => (await chrome.scripting.getRegisteredContentScripts()).map(s => s.id));
  const s = await settings();
  const last = await sw.evaluate(async () => (await chrome.storage.local.get('lastError')).lastError);
  check('Q1 duplicate list ids in a backup keep element hiding working',
    res.ok && scripts.includes('bouclier-css-ads') && scripts.includes('bouclier-cosmetic') &&
    scripts.filter(id => id === 'bouclier-css-ads').length === 1 && new Set(s.enabledLists).size === s.enabledLists.length && !last,
    JSON.stringify({ res, scripts, lists: s.enabledLists, last }));
  await resetAll();
}

// Q2 selectors that would hide the whole page, or break out of the style rule, are refused
{
  const unsafe = ['##body', '##html, body', 'news.test##:root', '##*', '##html > body', '##.a;@import url(x)', '##.b@media'];
  const res = await msg({ type: 'options:saveCustom', text: unsafe.join('\n') });
  await reset();
  await web.goto('http://news.test/', { waitUntil: 'load' });
  await web.waitForTimeout(700);
  const bodyShown = await disp('body');
  check('Q2 whole-page and injection selectors are refused', res.ok && res.errors.length === unsafe.length && bodyShown !== 'none',
    JSON.stringify({ errors: res.errors, bodyShown }));
  const safe = await sw.evaluate(() => [isSafeSelector('.ad-body'), isSafeSelector('div[data-x="a;b"]'), isSafeSelector('body > .ad'), isSafeSelector('#main .x')]);
  check('Q2b ordinary selectors (including ones mentioning body) still pass', safe.every(Boolean), JSON.stringify(safe));
}

// Q3 hosts-file lines are understood, comments skipped
{
  const res = await msg({ type: 'options:saveCustom', text: '# my hosts\n0.0.0.0 widgets.test\n127.0.0.1 localhost\n::1 localhost\n0.0.0.0 fonts.test # fonts' });
  const rules = await dynamicRules();
  await reset();
  await web.goto('http://news.test/', { waitUntil: 'load' });
  await web.waitForTimeout(700);
  const hits = await log();
  check('Q3 hosts-file lines block their host', res.ok && res.errors.length === 0 &&
    rules.some(r => r.condition.urlFilter === '||widgets.test^' && !r.condition.requestDomains) &&
    rules.some(r => r.condition.urlFilter === '||fonts.test^' && !r.condition.requestDomains) &&
    !hits.some(h => h.includes('widgets.test')), JSON.stringify({ errors: res.errors, n: rules.length, hits: hits.filter(h => /widgets|fonts/.test(h)) }));
  const spaced = await msg({ type: 'options:saveCustom', text: '/ads banner/' });
  check('Q3b a pattern with spaces is reported, not silently ignored', spaced.errors.length === 1 && /spaces/.test(spaced.errors[0]), JSON.stringify(spaced.errors));
}

// Q4 "~site##selector" hides everywhere except that site
{
  await msg({ type: 'options:saveCustom', text: '~news.test##.game' });
  await web.goto('http://1001games.com/', { waitUntil: 'load' });
  await web.waitForTimeout(800);
  const gameHidden = (await disp('#game')) === 'none';
  await msg({ type: 'options:saveCustom', text: '~news.test##.keep-me' });
  await web.goto('http://news.test/', { waitUntil: 'load' });
  await web.waitForTimeout(800);
  const keepShown = (await disp('#keep')) !== 'none';
  await msg({ type: 'options:saveCustom', text: '~1001games.com##.keep-me' });
  await web.goto('http://news.test/', { waitUntil: 'load' });
  await web.waitForTimeout(800);
  const keepHidden = (await disp('#keep')) === 'none';
  check('Q4 "everywhere except" element hiding works both ways', gameHidden && keepShown && keepHidden, JSON.stringify({ gameHidden, keepShown, keepHidden }));
}

// Q5 international site names and names with a port or path
{
  const out = await sw.evaluate(() => {
    const a = parseCustomFilters('bücher.de##.ad\nexample.com:8080##.x\nhttps://example.org/##.y\nexample.*##.z');
    return { keys: Object.keys(a.cosmetic.specific), errors: a.errors };
  });
  check('Q5 IDN sites become punycode, ports and paths are reported', out.keys.includes('xn--bcher-kva.de') && out.keys.includes('example.*') && out.errors.length === 2, JSON.stringify(out));
}

// Q6 addresses that cannot be paused say so instead of pretending
{
  const a = await msg({ type: 'popup:toggleSite', host: '[::1]' });
  const b = await msg({ type: 'popup:toggleSite', host: 'my_host.example.com' });
  const c = await msg({ type: 'popup:pauseSiteFor', host: '[::1]', minutes: 60 });
  const s = await settings();
  check('Q6 unpausable addresses are refused with a reason', a.ok === false && /cannot pause/.test(a.error) && b.ok === false && c.ok === false && !(s.pausedSites || []).length,
    JSON.stringify({ a, b, c, paused: s.pausedSites }));
}

// Q7 "www." is only dropped when what is left is a real site
{
  const keys = await sw.evaluate(() => ['www.gov.uk', 'www.bbc.co.uk', 'www.example.com', 'www2.lemonde.fr', 'www.co.uk', 'www.gouv.fr', 'www'].map(siteKey));
  check('Q7 pausing www.gov.uk does not pause every .gov.uk site',
    JSON.stringify(keys) === JSON.stringify(['www.gov.uk', 'bbc.co.uk', 'example.com', 'lemonde.fr', 'www.co.uk', 'www.gouv.fr', 'www']), JSON.stringify(keys));
}

// Q8 very long filter lists are cut at the end of a line, and the user is told
{
  const lines = [];
  for (let i = 0; lines.join('\n').length < 230000; i++) lines.push(`||tracker-${i}.example^$third-party`);
  const text = lines.join('\n');
  const res = await msg({ type: 'options:saveCustom', text });
  const stored = (await settings()).customFilters;
  const whole = stored.split('\n').every(l => lines.includes(l));
  check('Q8 filters over the limit are cut between lines and reported', res.ok && /Only the first/.test(res.errors[0] || '') && stored.length <= 200000 && whole,
    JSON.stringify({ ok: res.ok, first: res.errors[0], len: stored.length, whole }));
  await msg({ type: 'options:saveCustom', text: '' });
}

// Q9 the dynamic-rule cap: paused sites first, the rest dropped with a message
{
  const out = await sw.evaluate(() => {
    const custom = parseCustomFilters('a1.test\na2.test\na3.test\na4.test');
    const rules = buildDynamicRules({ pausedSites: ['p1.test', 'p2.test'], siteControls: { 's.test': { fonts: true, scripts3p: true } } }, custom, 4);
    return { n: rules.length, first: rules.slice(0, 2).map(r => r.action.type), errors: custom.errors };
  });
  check('Q9 over the rule cap: paused sites kept first, the rest reported', out.n === 4 && out.first.every(t => t === 'allowAllRequests') && out.errors.some(e => /last 4/.test(e)),
    JSON.stringify(out));
}

/* ---------------------------------------------------------------- races and stress */

// Q10 three site controls changed at the same moment are all kept
{
  await Promise.all(['fonts', 'scripts3p', 'comments'].map(key => msg({ type: 'popup:siteControl', host: 'news.test', key, value: true })));
  const c = (await settings()).siteControls['news.test'] || {};
  check('Q10 simultaneous changes never overwrite each other', c.fonts && c.scripts3p && c.comments, JSON.stringify(c));
  await msg({ type: 'options:removeSiteControls', site: 'news.test' });
}

// Q11 the popup says which state the user chose, so it can never flip a pause the wrong way
{
  const seq = [];
  for (const p of [false, true, true, false, false]) {
    await msg({ type: 'popup:setPause', paused: p });
    seq.push((await settings()).paused);
  }
  await msg({ type: 'popup:setSitePaused', host: 'www.news.test', paused: true });
  await msg({ type: 'popup:setSitePaused', host: 'news.test', paused: true });
  const once = (await settings()).pausedSites;
  await msg({ type: 'popup:setSitePaused', host: 'news.test', paused: false });
  const none = (await settings()).pausedSites;
  check('Q11 explicit pause and resume are idempotent', JSON.stringify(seq) === '[false,true,true,false,false]' && JSON.stringify(once) === '["news.test"]' && none.length === 0,
    JSON.stringify({ seq, once, none }));
}

// Q12 50 quick toggles of one site end in the right state, rules and settings agreeing
{
  const flips = n => Promise.all(Array.from({ length: n }, () => msg({ type: 'popup:toggleSite', host: 'stress.test' })));
  await flips(50);
  let s = await settings();
  let rules = (await dynamicRules()).filter(r => r.condition.urlFilter === '||stress.test^');
  const evenOk = !s.pausedSites.includes('stress.test') && rules.length === 0;
  await flips(51);
  s = await settings();
  rules = (await dynamicRules()).filter(r => r.condition.urlFilter === '||stress.test^');
  const oddOk = s.pausedSites.filter(x => x === 'stress.test').length === 1 && rules.length === 1;
  check('Q12 50 and 51 rapid toggles: state and rules agree', evenOk && oddOk, JSON.stringify({ evenOk, oddOk, paused: s.pausedSites, rules: rules.length }));
  await msg({ type: 'popup:setSitePaused', host: 'stress.test', paused: false });
}

// Q13 100 settings changes fired at once: no error, nothing lost
{
  const t0 = Date.now();
  await Promise.all(Array.from({ length: 100 }, (_, i) => msg({ type: 'popup:siteControl', host: `s${i}.stress.test`, key: 'fonts', value: true })));
  const s = await settings();
  const n = Object.keys(s.siteControls).filter(k => k.endsWith('.stress.test')).length;
  const rules = (await dynamicRules()).filter(r => r.id >= 30000).length;
  const last = await sw.evaluate(async () => (await chrome.storage.local.get('lastError')).lastError);
  check('Q13 100 simultaneous changes all land, rules match', n === 100 && rules === 100 && !last, JSON.stringify({ n, rules, last, ms: Date.now() - t0 }));
  await resetAll();
}

/* ---------------------------------------------------------------- older Safari fallbacks */

// Q14 Safari 16.4-17 has no cssOrigin: registering content scripts retries without it
{
  const out = await sw.evaluate(async () => {
    const real = chrome.scripting.registerContentScripts.bind(chrome.scripting);
    let refused = 0;
    chrome.scripting.registerContentScripts = async scripts => {
      if (scripts.some(s => 'cssOrigin' in s)) { refused++; throw new Error('Invalid call to scripting.registerContentScripts(): unknown key'); }
      return real(scripts);
    };
    try {
      const res = await applySettings({ force: true });
      const ids = (await chrome.scripting.getRegisteredContentScripts()).map(s => s.id);
      return { res, refused, ids };
    } finally {
      chrome.scripting.registerContentScripts = real;
    }
  });
  check('Q14 content scripts register on Safari without cssOrigin', out.res.ok && out.refused >= 1 && out.ids.includes('bouclier-css-ads') && out.ids.includes('bouclier-cosmetic'), JSON.stringify(out));
}

// Q15 Safari 16.4-17 has no insertCSS origin: site-specific hiding still applies
{
  await sw.evaluate(() => {
    const real = chrome.scripting.insertCSS.bind(chrome.scripting);
    globalThis.__realInsertCSS = real;
    chrome.scripting.insertCSS = async d => { if ('origin' in d) throw new Error('unknown key origin'); return real(d); };
  });
  await web.goto('http://1001games.com/', { waitUntil: 'load' });
  await web.waitForTimeout(900);
  const hidden = (await disp('#specific-hide')) === 'none';
  await sw.evaluate(() => { chrome.scripting.insertCSS = globalThis.__realInsertCSS; });
  check('Q15 site-specific hiding works without insertCSS origin', hidden);
}

// Q16 the Settings path on iPhones follows the iOS version
{
  const paths = {};
  for (const [name, ua] of [['ios16', 'Mozilla/5.0 (iPhone; CPU iPhone OS 16_7_10 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1'],
    ['ios17', 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_7 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.7 Mobile/15E148 Safari/604.1'],
    ['ios26', 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Mobile/15E148 Safari/604.1']]) {
    const p = await ctx.newPage();
    const cdp = await ctx.newCDPSession(p);
    await cdp.send('Emulation.setUserAgentOverride', { userAgent: ua });
    await p.goto(`chrome-extension://${extId}/pages/welcome.html`);
    paths[name] = await p.evaluate(() => globalThis.BOUCLIER_PLATFORM.extensionSettings);
    await p.close();
  }
  check('Q16 iOS 16/17 get "Settings → Safari", iOS 18+ "Settings → Apps → Safari"',
    paths.ios16 === 'Settings → Safari → Extensions → Bouclier' && paths.ios17 === 'Settings → Safari → Extensions → Bouclier' && paths.ios26 === 'Settings → Apps → Safari → Extensions → Bouclier',
    JSON.stringify(paths));
}

// Q31 Safari before 26 gets each list's "_compat" ruleset (every domain its own rule: iOS 18.5 blocked 16 %
//     of the test page with the regular rules, 100 % with these). Switched in, ads still blocked and the
//     page still loads; switched back, the regular rulesets return. (Chromium stands in for Safari here.)
{
  await msg({ type: 'debug:compat', on: true });
  const compatSets = await sw.evaluate(async () => chrome.declarativeNetRequest.getEnabledRulesets());
  const st = await sw.evaluate(async () => (await chrome.storage.local.get('state')).state);
  await reset();
  await web.goto('http://news.test/', { waitUntil: 'load' });
  await web.waitForTimeout(800);
  const hits = await log();
  await msg({ type: 'debug:compat', on: null });
  const regular = await sw.evaluate(async () => chrome.declarativeNetRequest.getEnabledRulesets());
  const blocked = ['googletagservices.com', 'google-analytics.com', 'googlesyndication.com'].every(h => !hits.some(x => x.includes(h)));
  const loaded = hits.some(x => x.includes('cdn.news.test')) && hits.some(x => x.includes('news.test/logo.png'));
  check('Q31 Safari before 26: the compat rulesets replace the regular ones, still block, and switch back',
    compatSets.length >= 5 && compatSets.every(id => id.endsWith('_compat')) && (st.problems || []).length === 0 &&
    regular.length === compatSets.length && regular.every(id => !id.endsWith('_compat')) && blocked && loaded,
    JSON.stringify({ compatSets, regular, problems: st.problems, blocked, loaded, hits: hits.filter(h => /google|news/.test(h)) }));
}

/* ---------------------------------------------------------------- small screens and accessibility */

const IPHONE8_UA = 'Mozilla/5.0 (iPhone; CPU iPhone OS 16_7_10 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1';
async function iphone8(url) {
  const p = watch(await ctx.newPage());
  const cdp = await ctx.newCDPSession(p);
  await cdp.send('Emulation.setUserAgentOverride', { userAgent: IPHONE8_UA });
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 375, height: 667, deviceScaleFactor: 2, mobile: true });
  await cdp.send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 5 });
  await p.addInitScript(() => Object.defineProperty(Navigator.prototype, 'maxTouchPoints', { get: () => 5, configurable: true }));
  await p.goto(url);
  await p.waitForTimeout(900);
  return { p, cdp };
}

// Q17 popup on a 4.7-inch iPhone: a very long site name ends with … and the switch stays reachable
{
  const { p } = await iphone8(`chrome-extension://${extId}/popup/popup.html`);
  const out = await p.evaluate(() => {
    document.getElementById('host').textContent = 'lesjoursheureux-de-montpellier.blogspot.com';
    const sw = document.getElementById('site-toggle').parentElement.getBoundingClientRect();
    return { overflow: document.documentElement.scrollWidth - innerWidth, switchRight: Math.round(sw.right), vw: innerWidth };
  });
  const small = await p.evaluate(() => [...document.querySelectorAll('button, .pill, .chip, .icon-btn')]
    .filter(e => e.offsetParent !== null)
    .map(e => ({ id: e.id || e.className, h: Math.round(e.getBoundingClientRect().height), w: Math.round(e.getBoundingClientRect().width) }))
    .filter(b => b.h < 44 || b.w < 44));
  check('Q17 iPhone 8 popup: no sideways scrolling, switch on screen', out.overflow <= 0 && out.switchRight <= out.vw, JSON.stringify(out));
  check('Q17b iPhone 8 popup: every visible button is at least 44×44 points', small.length === 0, JSON.stringify(small));
  const named = await p.evaluate(() => ['pause-all', 'site-toggle'].map(id => {
    const e = document.getElementById(id);
    return e.getAttribute('aria-label') || (e.labels && [...e.labels].map(l => l.textContent.trim()).join(' ')) || '';
  }));
  check('Q17c popup switches have accessible names', named.every(n => n.length > 0), JSON.stringify(named));
  await p.close();
}

// Q18 settings page on a 4.7-inch iPhone: the chart tip stays on screen and labels are readable
{
  const { p } = await iphone8(`chrome-extension://${extId}/options/options.html`);
  const out = await p.evaluate(async () => {
    const hits = [...document.querySelectorAll('.chart .bar-hit')];
    const last = hits[hits.length - 1];
    if (last) last.dispatchEvent(new MouseEvent('mouseenter'));
    await new Promise(r => setTimeout(r, 50));
    const axis = document.querySelector('.chart .axis');
    return {
      overflow: document.documentElement.scrollWidth - innerWidth,
      axisPx: axis ? parseFloat(getComputedStyle(axis).fontSize) : null,
      importIsButton: document.getElementById('import-button') && document.getElementById('import-button').tagName,
    };
  });
  check('Q18 iPhone 8 settings: chart tip inside the page, axis text ≥ 11 px, import is a real button', out.overflow <= 0 && out.axisPx >= 11 && out.importIsButton === 'BUTTON', JSON.stringify(out));
  await p.close();
}

// Q19 filters typed but not saved survive flipping a switch on the settings page
{
  const p = watch(await ctx.newPage());
  await p.goto(`chrome-extension://${extId}/options/options.html`);
  await p.waitForTimeout(900);
  await p.fill('#custom', 'typed-but-not-saved.example');
  await p.evaluate(() => document.getElementById('custom').blur());
  const box = await p.$('input[type=checkbox]:not([disabled])');
  if (box) await box.evaluate(e => e.click());
  await p.waitForTimeout(1500);
  const value = await p.$eval('#custom', e => e.value);
  check('Q19 unsaved filters are kept when the page reloads its data', value === 'typed-but-not-saved.example', value);
  if (box) await box.evaluate(e => e.click());
  await p.waitForTimeout(800);
  await p.close();
}

// Q20 the app window (appstore/app) on iPhone 8, iOS 26.2+ and macOS 12 wording
{
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'bouclier-app-'));
  fs.mkdirSync(path.join(dir, 'Base.lproj'));
  const app = path.resolve(here, '../appstore/app');
  fs.copyFileSync(path.join(app, 'Main.html'), path.join(dir, 'Base.lproj', 'Main.html'));
  for (const f of ['Style.css', 'Script.js']) fs.copyFileSync(path.join(app, f), path.join(dir, f));
  fs.copyFileSync(path.resolve(here, '../extension/icons/icon-128.png'), path.join(dir, 'Icon.png'));
  const { p } = await iphone8(`file://${dir}/Base.lproj/Main.html`);
  const ios = await p.evaluate(() => {
    const vis = sel => getComputedStyle(document.querySelector(sel)).display !== 'none';
    const before = vis('.ios-direct');
    show('ios', undefined, true, true);
    const after = vis('.ios-direct');
    return { before, after, path: document.querySelector('.ios-settings-path').textContent, overflow: document.documentElement.scrollWidth - innerWidth, tall: document.documentElement.scrollHeight };
  });
  check('Q20 app on iPhone 8: iOS 16 path, no sideways scroll, Settings button only on iOS 26.2+',
    !ios.before && ios.after && ios.path === 'Settings → Safari → Extensions' && ios.overflow <= 0, JSON.stringify(ios));
  const mac = await p.evaluate(() => {
    document.body.className = '';
    show('mac', false, false);
    const texts = [...document.querySelectorAll('.platform-mac')].filter(e => getComputedStyle(e).display !== 'none').map(e => e.textContent).join(' ');
    return { preferences: /Preferences/.test(texts), settings: /Settings/.test(texts) };
  });
  check('Q20b app on macOS 12 says "Preferences"', mac.preferences && !mac.settings, JSON.stringify(mac));
  await p.close();
}

/* ---------------------------------------------------------------- dynamic-rule cap, end to end */

// Q21 more site controls than the browser accepts: settings still apply and pausing still works
{
  const max = await sw.evaluate(() => MAX_DYNAMIC_RULES);
  const siteControls = {};
  for (let i = 0; i < Math.ceil(max / 2) + 3; i++) siteControls[`c${i}.cap.test`] = { fonts: true, scripts3p: true, comments: false };
  const res = await msg({ type: 'options:import', data: { format: 'bouclier-settings', version: 1, settings: { siteControls } } });
  const toggled = await msg({ type: 'popup:setSitePaused', host: 'news.test', paused: true });
  const rules = await dynamicRules();
  const state = await sw.evaluate(async () => (await chrome.storage.local.get('state')).state);
  const pauseRule = rules.some(r => r.condition.urlFilter === '||news.test^' && r.action.type === 'allowAllRequests');
  check('Q21 over the dynamic-rule cap: applied up to the cap, pausing still works, the user is told',
    res.ok && toggled.ok && rules.length === max && pauseRule && (state.customErrors || []).some(e => /accepts/.test(e)),
    JSON.stringify({ max, res: { ok: res.ok, error: res.error }, toggled, n: rules.length, pauseRule, errors: state && state.customErrors }));
  await resetAll();
}

/* ---------------------------------------------------------------- network drops and rapid taps */

// Q24 no network: pages fail to load, Bouclier's own pages and state keep working
{
  await ctx.setOffline(true);
  const failed = await web.goto('http://news.test/', { waitUntil: 'load', timeout: 8000 }).then(() => false, () => true);
  const refused = await web.goto('http://closed.test:81/', { waitUntil: 'load', timeout: 8000 }).then(() => false, () => true);
  const st = await msg({ type: 'popup:state', tabId: null });
  const p = watch(await ctx.newPage());
  await p.goto(`chrome-extension://${extId}/options/options.html`);
  await p.waitForTimeout(900);
  const optionsOk = (await p.$eval('#stat-all', e => e.textContent)) !== '';
  await p.close();
  await ctx.setOffline(false);
  const back = await web.goto('http://news.test/', { waitUntil: 'load' }).then(() => true, () => false);
  check('Q24 offline and refused connections: no crash, popup state and settings still load, recovery when back online',
    failed && refused && st && st.version && optionsOk && back, JSON.stringify({ failed, refused, version: st && st.version, optionsOk, back }));
}

// Q25 21 rapid taps on the "pause everywhere" switch in the real popup: the end state matches the switch
{
  const p = watch(await ctx.newPage());
  await p.goto(`chrome-extension://${extId}/popup/popup.html`);
  await p.waitForTimeout(700);
  // In Safari the menu belongs to the web page's tab; opened as a tab here, point it at the web page,
  // or its "reload the page" step would reload the menu itself.
  const webTabId = await sw.evaluate(async () => (await chrome.tabs.query({})).find(t => (t.url || '').startsWith('http://news.test/')).id);
  await p.evaluate(async id => { tab = await chrome.tabs.get(id); }, webTabId);
  for (let i = 0; i < 21; i++) {
    await p.evaluate(() => document.getElementById('pause-all').click());
    await p.waitForTimeout(15);
  }
  // let every queued change finish, then compare the saved setting with what the popup shows
  let paused = null;
  let shown = null;
  for (let i = 0; i < 40; i++) {
    await p.waitForTimeout(250);
    paused = (await settings()).paused;
    shown = await p.$eval('#pause-all', e => e.checked);
    const busy = await p.evaluate(() => document.body.getAttribute('aria-busy'));
    if (!busy && paused === shown) break;
  }
  check('Q25 rapid taps: the saved pause state and the switch agree', paused === shown, JSON.stringify({ paused, shown }));
  await msg({ type: 'popup:setPause', paused: false });
  await p.close();
}

/* ---------------------------------------------------------------- website access and timed site pause (2026-09-29) */

// The popup opened as a tab, pointed at the news.test page the way Safari's menu belongs to the page's tab.
const newsTab = async () => {
  await web.goto('http://news.test/', { waitUntil: 'load' });
  return sw.evaluate(async () => (await chrome.tabs.query({})).find(t => (t.url || '').startsWith('http://news.test/')).id);
};
async function popupFor(tabId) {
  const p = watch(await ctx.newPage());
  await p.goto(`chrome-extension://${extId}/popup/popup.html`);
  await p.waitForTimeout(500);
  await p.evaluate(async id => { tab = await chrome.tabs.get(id); await refresh(); }, tabId);
  return p;
}
async function idle(p) {
  for (let i = 0; i < 40; i++) {
    await p.waitForTimeout(150);
    if (!(await p.evaluate(() => document.body.getAttribute('aria-busy')))) return;
  }
}
// What WebKit answers (tests/webkit-bench/PermissionProbe.swift): Safari stores "Always Allow on Every Website"
// as *://*/*, and permissions.contains() does not count that as <all_urls>.
const safariGrant = grantedEverywhere => sw.evaluate(everywhere => {
  globalThis.__realContains = globalThis.__realContains || chrome.permissions.contains;
  chrome.permissions.contains = async p => everywhere && JSON.stringify((p && p.origins) || []) === '["*://*/*"]';
}, grantedEverywhere);
const realGrant = () => sw.evaluate(() => { if (globalThis.__realContains) chrome.permissions.contains = globalThis.__realContains; });

// Q26 a Mac already allowed on every website (Safari's *://*/*) gets no "Allow" banner, and "Hide an element" works
{
  const tabId = await newsTab();
  await safariGrant(true);
  const stubbed = await sw.evaluate(async () => [await chrome.permissions.contains({ origins: ['<all_urls>'] }), await hasAllSitesAccess()]);
  const p = await popupFor(tabId);
  const out = await p.evaluate(() => ({ banner: !document.getElementById('access-banner').hidden, pickDisabled: document.getElementById('pick').disabled }));
  check('Q26 Safari\'s "every website" grant counts as allowed: no banner, picker usable',
    stubbed[0] === false && stubbed[1] === true && !out.banner && !out.pickDisabled, JSON.stringify({ stubbed, ...out }));
  await p.close();
}

// Q26b "Allow" that Safari answers without granting (WebKit resolves true when one site is allowed) says where to do it by hand
{
  const tabId = await newsTab();
  await safariGrant(false);
  const p = await popupFor(tabId);
  await p.evaluate(() => { chrome.permissions.request = async () => true; });
  const before = await p.evaluate(() => !document.getElementById('access-banner').hidden);
  await p.click('#grant');
  await p.waitForTimeout(600);
  const after = await p.evaluate(() => ({
    banner: !document.getElementById('access-banner').hidden,
    button: !document.getElementById('grant').hidden,
    text: document.getElementById('access-text').textContent,
  }));
  check('Q26b an "Allow" that changes nothing shows the Safari Settings steps instead of doing nothing',
    before && after.banner && !after.button && /Always Allow on Every Website/.test(after.text) && /Allow/.test(after.text), JSON.stringify({ before, ...after }));
  await p.close();
}

// Q26c "Allow" that Safari grants hides the banner and thanks the user
{
  const tabId = await newsTab();
  await safariGrant(false);
  const p = await popupFor(tabId);
  const before = await p.evaluate(() => !document.getElementById('access-banner').hidden);
  await safariGrant(true);   // the user answers "Always Allow on Every Website" to Safari's question
  await p.evaluate(() => { chrome.permissions.request = async () => true; });
  await p.click('#grant');
  await p.waitForTimeout(600);
  const after = await p.evaluate(() => ({ banner: !document.getElementById('access-banner').hidden, status: document.getElementById('status').textContent }));
  check('Q26c a granted "Allow" hides the banner', before && !after.banner && /Thanks/.test(after.status), JSON.stringify({ before, ...after }));
  await realGrant();
  await p.close();
}

// Q26e a site allowed on its own ("Always Allow on This Website"): the banner stays, but "Hide an element" works there
{
  const tabId = await newsTab();
  await sw.evaluate(() => {
    globalThis.__realContains = globalThis.__realContains || chrome.permissions.contains;
    chrome.permissions.contains = async p => JSON.stringify((p && p.origins) || []) === '["http://news.test/*"]';
  });
  const p = await popupFor(tabId);
  const out = await p.evaluate(() => ({ banner: !document.getElementById('access-banner').hidden, pickDisabled: document.getElementById('pick').disabled }));
  check('Q26e one site allowed: banner shown, "Hide an element" usable on that site', out.banner && !out.pickDisabled, JSON.stringify(out));
  await realGrant();
  await p.close();
}

// Q26d the setup check page agrees; without access it tells a Mac user where to allow it by hand
{
  await safariGrant(true);
  const w = watch(await ctx.newPage());
  await w.goto(`chrome-extension://${extId}/pages/welcome.html`);
  await w.waitForTimeout(700);
  const items = await w.$$eval('#checks li', els => els.map(e => e.className + ':' + e.querySelector('.title').textContent));
  await safariGrant(false);
  await w.reload();
  await w.waitForTimeout(700);
  const todo = await w.$$eval('#checks li.todo .desc', els => els.map(e => e.textContent));
  check('Q26d setup check: Safari\'s grant counts; without it the Safari Settings steps are shown',
    items.some(t => t.startsWith('ok:Allowed on every website')) && todo.some(t => /Always Allow on Every Website/.test(t)), JSON.stringify({ items, todo }));
  await realGrant();
  await w.close();
}

// Q27 "Pause on this site for 2 hours" in the popup's main view: this site loads everything, other sites stay blocked
{
  const tabId = await newsTab();
  const p = await popupFor(tabId);
  const chips = await p.$$eval('#site-pause .chip', els => els.map(e => e.textContent));
  const shownBefore = await p.evaluate(() => !document.getElementById('site-pause').hidden);
  const t0 = Date.now();
  await p.click('#site-pause .chip[data-minutes="120"]');
  await idle(p);
  const s = await settings();
  const until = (s.sitePauseUntil || {})['news.test'] || 0;
  const alarm = await sw.evaluate(async () => (await chrome.alarms.get('resume-site|news.test')) || null);
  const view = await p.evaluate(() => ({
    count: document.getElementById('count').textContent,
    chipsHidden: document.getElementById('site-pause').hidden,
    switchOn: document.getElementById('site-toggle').checked,
  }));
  await reset();
  await web.goto('http://news.test/', { waitUntil: 'load' });
  await web.waitForTimeout(700);
  const newsHits = await log();
  await reset();
  await web.goto('http://1001games.com/', { waitUntil: 'load' });
  await web.evaluate(() => new Promise(r => { const s = document.createElement('script'); s.src = 'http://www.googletagservices.com/tag/js/gpt.js'; s.onload = s.onerror = r; document.head.append(s); }));
  const otherHits = await log();
  check('Q27 the site-pause chips are in the main view: 1 hour, 2 hours, Until tomorrow',
    shownBefore && JSON.stringify(chips) === '["1 hour","2 hours","Until tomorrow"]', JSON.stringify({ shownBefore, chips }));
  check('Q27b "2 hours" pauses news.test for two hours, with a wake-up to protect it again',
    s.pausedSites.includes('news.test') && until >= t0 + 119 * 60000 && until <= Date.now() + 121 * 60000 &&
    alarm && Math.abs(alarm.scheduledTime - until) < 2000 && /Paused on this site until/.test(view.count) && view.chipsHidden && !view.switchOn,
    JSON.stringify({ paused: s.pausedSites, until, alarm, view }));
  check('Q27c the paused site loads its ads, another site stays blocked',
    newsHits.some(h => h.includes('googletagservices.com')) && !otherHits.some(h => h.includes('googletagservices.com')),
    JSON.stringify({ news: newsHits.filter(h => /googletag|analytics|pagead/.test(h)), other: otherHits.filter(h => /googletag/.test(h)) }));
  // the big switch protects the site again at once
  await p.evaluate(() => document.getElementById('site-toggle').click());
  await idle(p);
  const s2 = await settings();
  const alarm2 = await sw.evaluate(async () => (await chrome.alarms.get('resume-site|news.test')) || null);
  check('Q27d the switch ends a timed pause at once (settings, timer and wake-up cleared)',
    !s2.pausedSites.includes('news.test') && !(s2.sitePauseUntil || {})['news.test'] && !alarm2, JSON.stringify({ paused: s2.pausedSites, until: s2.sitePauseUntil, alarm2 }));
  await p.close();
}

// Q27e Safari takes seconds to recompile the rules, and a click outside closes the menu: the background
// reloads the page itself once the pause is applied, so a menu closed right after the click still works.
// The background's rule update and reload are timed, so a failure shows which came first.
{
  const tabId = await newsTab();
  await msg({ type: 'popup:setSitePaused', host: 'news.test', paused: false });
  await web.waitForTimeout(300);   // nothing of the previous test still loading in the tab
  const p = await popupFor(tabId);
  await sw.evaluate(() => {
    globalThis.__timeline = [];
    if (!globalThis.__timed) {
      globalThis.__timed = true;
      for (const [obj, name] of [[chrome.declarativeNetRequest, 'updateDynamicRules'], [chrome.tabs, 'reload']]) {
        const f = obj[name].bind(obj);
        obj[name] = async (...a) => { const r = await f(...a); __timeline.push([name, Date.now()]); return r; };
      }
    }
  });
  await reset();
  const t0 = Date.now();
  await p.click('#site-pause .chip[data-minutes="60"]');
  await p.close();
  let hits = [];
  for (let i = 0; i < 40 && !hits.some(h => h.includes('googletagservices.com')); i++) {
    await web.waitForTimeout(250);
    hits = await log();
  }
  const s = await settings();
  const timeline = (await sw.evaluate(() => globalThis.__timeline)).map(([n, t]) => [n, t - t0]);
  const rulesAt = (timeline.find(([n]) => n === 'updateDynamicRules') || [])[1];
  const reloadAt = (timeline.find(([n]) => n === 'reload') || [])[1];
  check('Q27e menu closed right after "1 hour": the page is still reloaded, after the rules changed, now without blocking',
    s.pausedSites.includes('news.test') && rulesAt >= 0 && reloadAt >= rulesAt &&
    hits.some(h => h.startsWith('GET news.test/')) && hits.some(h => h.includes('googletagservices.com')),
    JSON.stringify({ paused: s.pausedSites, timeline, hits: hits.filter(h => !h.includes('__log')).slice(0, 12) }));
  await msg({ type: 'popup:setSitePaused', host: 'news.test', paused: false });
  const who = await sw.evaluate(() => [isExtensionPage({ url: 'http://news.test/' }), isExtensionPage({ url: chrome.runtime.getURL('popup/popup.html') })]);
  check('Q27f only Bouclier\'s own pages can ask for a page reload', who[0] === false && who[1] === true, JSON.stringify(who));
}

// Q28 "Until tomorrow" ends at 6:00 the next morning; nonsense durations are refused
{
  const tabId = await newsTab();
  const p = await popupFor(tabId);
  await p.click('#site-pause .chip[data-until="tomorrow"]');
  await idle(p);
  const until = ((await settings()).sitePauseUntil || {})['news.test'] || 0;
  const want = new Date(); want.setDate(want.getDate() + 1); want.setHours(6, 0, 0, 0);
  await msg({ type: 'popup:setSitePaused', host: 'news.test', paused: false });
  const bad = [];
  for (const minutes of [0, -30, 'abc', null, 1e9, Infinity]) {
    const r = await msg({ type: 'popup:pauseSiteFor', host: 'news.test', minutes });
    if (!r || r.ok !== false) bad.push(minutes);
  }
  const left = (await settings()).pausedSites;
  check('Q28 "Until tomorrow" ends at 6:00 tomorrow; zero, negative, text and huge durations are refused',
    until === want.getTime() && bad.length === 0 && !left.includes('news.test'), JSON.stringify({ until, want: want.getTime(), bad, left }));
  await p.close();
}

// Q29 the site-pause chips on a 4.7-inch iPhone: one row, 44-point targets, nothing off screen
{
  const tabId = await newsTab();
  const { p } = await iphone8(`chrome-extension://${extId}/popup/popup.html`);
  await p.evaluate(async id => { tab = await chrome.tabs.get(id); await refresh(); }, tabId);
  const out = await p.evaluate(() => {
    const chips = [...document.querySelectorAll('#site-pause .chip')].map(e => e.getBoundingClientRect());
    return {
      rows: new Set(chips.map(r => Math.round(r.top))).size,
      minH: Math.min(...chips.map(r => r.height)),
      right: Math.max(...chips.map(r => r.right)),
      vw: innerWidth,
      overflow: document.documentElement.scrollWidth - innerWidth,
    };
  });
  check('Q29 iPhone 8: the three site-pause chips fit one row, 44 points tall', out.rows === 1 && out.minH >= 44 && out.right <= out.vw && out.overflow <= 0, JSON.stringify(out));
  await p.close();
}

/* ---------------------------------------------------------------- sites Daniel reported (2026-09-29) */

// Q30 BilletReduc's own "Sponsorisé" banners (its "max" ad server): hidden and their images never loaded;
// the rest of the page (carousel, rows of shows, show pictures) untouched
{
  await reset();
  await web.goto('http://www.billetreduc.com/', { waitUntil: 'load' });
  await web.waitForTimeout(900);
  const shown = await web.evaluate(() => Object.fromEntries(['br-top', 'br-square', 'br-tile', 'br-slider', 'br-rail', 'br-show']
    .map(id => [id, getComputedStyle(document.getElementById(id)).display !== 'none'])));
  const hits = await log();
  check('Q30 BilletReduc: sponsored banners hidden and not loaded, the rest of the page kept',
    !shown['br-top'] && !shown['br-square'] && !shown['br-tile'] && shown['br-slider'] && shown['br-rail'] && shown['br-show'] &&
    !hits.some(h => h.includes('/zi/max/') || h.includes('/cgi/max.aspx')) && hits.some(h => h.includes('/zg/n250/show.jpeg')),
    JSON.stringify({ shown, hits: hits.filter(h => h.includes('billetreduc')) }));
}

/* ---------------------------------------------------------------- end */

const last = await sw.evaluate(async () => (await chrome.storage.local.get('lastError')).lastError);
check('Q22 no background errors after all of the above', !last, JSON.stringify(last));
check('Q23 no console errors on any page', pageErrors.length === 0, pageErrors.slice(0, 6).join(' | '));

await ctx.close();
const failed = results.filter(r => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
