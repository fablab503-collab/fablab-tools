import { chromium } from '/home/claude/.npm-global/lib/node_modules/playwright/index.mjs';
import path from 'path';
import fs from 'fs';
// Chromium writes _metadata/ into unpacked extensions, so test a throwaway copy.
const ext = fs.mkdtempSync('/tmp/bouclier-ext-');
fs.cpSync(path.resolve(path.dirname(new URL(import.meta.url).pathname), '../extension'), ext, { recursive: true });
// screenshots go next to this file (tests/*.png is ignored by git), wherever the suite is started from
const shotPath = name => path.join(path.dirname(new URL(import.meta.url).pathname), name);
const profile = fs.mkdtempSync('/tmp/bouclier-profile-');
const results = [];
const check = (name, ok, detail = '') => { results.push({ name, ok, detail }); console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`); };

const ctx = await chromium.launchPersistentContext(profile, {
  headless: true,
  channel: 'chromium',
  args: [
    `--disable-extensions-except=${ext}`, `--load-extension=${ext}`,
    '--host-resolver-rules=MAP * 127.0.0.1, EXCLUDE localhost', '--no-proxy-server',
  ],
});
let [sw] = ctx.serviceWorkers();
if (!sw) sw = await ctx.waitForEvent('serviceworker', { timeout: 15000 });
const extId = sw.url().split('/')[2];
console.log('extension id', extId);
// wait until background applied settings
for (let i = 0; i < 60; i++) {
  let st = null;
  try { st = await sw.evaluate(async () => (await chrome.storage.local.get('state')).state); } catch { /* worker still starting */ }
  if (st) { console.log('state', JSON.stringify(st)); break; }
  await new Promise(r => setTimeout(r, 500));
}
const errors = [];
const page = await ctx.newPage();
page.on('console', m => { if (m.type() === 'error' && !m.text().includes('ERR_BLOCKED_BY_CLIENT')) errors.push(m.text()); });
const log = async () => (await (await fetch('http://127.0.0.1/__log', { headers: { Host: 'x' } })).json());
const reset = () => fetch('http://127.0.0.1/__reset');

// 1. network blocking + generic hiding
await reset();
await page.goto('http://news.test/', { waitUntil: 'load' });
await page.waitForTimeout(800);
let hits = await log();
check('page loaded', hits.some(h => h.startsWith('GET news.test/')));
check('first-party asset allowed', hits.some(h => h.includes('news.test/logo.png')));
check('neutral third-party script allowed', hits.some(h => h.includes('cdn.news.test/app.js')));
check('googletagservices blocked (EasyList)', !hits.some(h => h.includes('googletagservices')), hits.filter(h=>h.includes('googletag')).join(','));
check('google-analytics blocked (EasyPrivacy)', !hits.some(h => h.includes('google-analytics')));
check('googlesyndication blocked', !hits.some(h => h.includes('googlesyndication')));
const disp = async sel => page.$eval(sel, e => getComputedStyle(e).display);
// What the blocker looked like when a timing-sensitive check failed (F2 and F7 each failed once in
// Chromium on 3 Oct 2026 and never in a stress run): enabled lists, dynamic rules, the tab's
// navigation time and its last matched rules (ms after it), and the ad/tracker requests the server saw.
const why = async tabId => {
  const d = await sw.evaluate(async tid => {
    const r = await chrome.declarativeNetRequest.getMatchedRules(tid == null ? {} : { tabId: tid }).catch(e => ({ error: String(e) }));
    const tabs = (await chrome.storage.session.get('tabState')).tabState || {};
    const nav = tid != null && tabs[tid] ? tabs[tid].nav : null;
    return {
      enabled: await chrome.declarativeNetRequest.getEnabledRulesets(),
      dynamic: (await chrome.declarativeNetRequest.getDynamicRules()).map(x => x.action.type + ':' + (x.condition.requestDomains || x.condition.initiatorDomains || [x.condition.urlFilter || x.condition.regexFilter || '']).join(',')),
      nav,
      matched: (r.rulesMatchedInfo || []).slice(-8).map(m => [m.rule.rulesetId, Math.round(m.timeStamp - (nav || 0))]),
      error: r.error || null,
    };
  }, tabId).catch(e => ({ evalError: String(e) }));
  const hits = (await log()).filter(h => /googletag|doubleclick|analytics|track/.test(h));
  return JSON.stringify(d) + ' server: ' + (hits.join(' | ') || 'none');
};
check('generic element hidden (.sponsored-post)', (await disp('#generic-hide')) === 'none');
check('normal element visible', (await disp('#keep')) !== 'none');

// 1b. Bouclier's own additions (tools/extra-*.txt) block hosts the lists miss, third-party only
await reset();
await page.evaluate(async () => {
  for (const h of ['an.facebook.com', 'auction.unityads.unity3d.com', 'metrika.yandex.ru', 'p1.parsely.com']) {
    try { await fetch(`http://${h}/fakepage.html`, { mode: 'no-cors' }); } catch { /* blocked */ }
  }
});
await page.waitForTimeout(400);
hits = await log();
check('Bouclier additions block missed trackers', !hits.some(h => /facebook|unityads|yandex|parsely/.test(h)), hits.join(' | '));

// 2. site-specific hiding (dynamic path)
await page.goto('http://1001games.com/', { waitUntil: 'load' });
await page.waitForTimeout(800);
check('site-specific element hidden (.gc-leaderboard on 1001games.com)', (await disp('#specific-hide')) === 'none');
check('other element visible', (await disp('#game')) !== 'none');

// 3. YouTube data pruning
await page.goto('http://www.youtube.com/watch?v=abc', { waitUntil: 'load' });
await page.waitForFunction(() => window.__done === true, null, { timeout: 8000 }).catch(() => {});
const yt = await page.evaluate(() => ({ initial: window.__initial, fetchJson: window.__fetchJson, fetchText: window.__fetchText, parse: window.__parse, xhr: window.__xhr }));
check('ytInitialPlayerResponse stripped', yt.initial && !/adPlacements|playerAds|adSlots/.test(yt.initial) && /videoId/.test(yt.initial), yt.initial);
check('fetch().json() stripped', yt.fetchJson && !/adPlacements|playerAds|"adSlots"/.test(yt.fetchJson) && /videoId/.test(yt.fetchJson), yt.fetchJson);
check('fetch().text() stripped', yt.fetchText && !/adPlacements|playerAds|"adSlots"/.test(yt.fetchText), yt.fetchText);
check('JSON.parse stripped', yt.parse && !/adPlacements/.test(yt.parse) && /"other":1/.test(yt.parse), yt.parse);
check('XHR responseText stripped', yt.xhr && !/adPlacements|playerAds|"adSlots"/.test(yt.xhr), yt.xhr);
const yt2 = await page.evaluate(() => ({ iframeFetch: window.__iframeFetch, iframeParse: window.__iframeParse, flags: window.__flags, timer: window.__timer }));
check('iframe fetch bypass closed', yt2.iframeFetch && !/adPlacements|playerAds|"adSlots"/.test(yt2.iframeFetch), yt2.iframeFetch);
check('iframe JSON.parse bypass closed', yt2.iframeParse === '{"x":1}', yt2.iframeParse);
check('experiment flags forced off', yt2.flags === '[false,false,7]', yt2.flags);
check('17 s ad-block delay shortened', typeof yt2.timer === 'number' && yt2.timer < 2000, String(yt2.timer));

// 4. pause on a site via the popup message API
const popup = await ctx.newPage();
await popup.goto(`chrome-extension://${extId}/popup/popup.html`);
await popup.waitForTimeout(500);
const resp = await popup.evaluate(async () => chrome.runtime.sendMessage({ type: 'popup:toggleSite', host: 'news.test' }));
check('toggleSite answered', resp && resp.ok, JSON.stringify(resp));
await reset();
await page.goto('http://news.test/', { waitUntil: 'load' });
await page.waitForTimeout(800);
hits = await log();
check('paused site: ad script loads', hits.some(h => h.includes('googletagservices')), hits.join(' | '));
check('paused site: generic element visible', (await disp('#generic-hide')) !== 'none');
await popup.evaluate(async () => chrome.runtime.sendMessage({ type: 'popup:toggleSite', host: 'news.test' }));
await reset();
await page.goto('http://news.test/', { waitUntil: 'load' });
await page.waitForTimeout(800);
hits = await log();
check('resumed site: ad script blocked again', !hits.some(h => h.includes('googletagservices')));

// 5. custom filters
const saved = await popup.evaluate(async () => chrome.runtime.sendMessage({ type: 'options:saveCustom', text: 'cdn.news.test\nnews.test##.keep-me\nbad$weirdopt' }));
check('custom filters saved with 1 error', saved.ok && saved.errors.length === 1, JSON.stringify(saved));
await reset();
await page.goto('http://news.test/', { waitUntil: 'load' });
await page.waitForTimeout(800);
hits = await log();
check('custom network block works', !hits.some(h => h.includes('cdn.news.test')));
check('custom hide works', (await disp('#keep')) === 'none');
await popup.evaluate(async () => chrome.runtime.sendMessage({ type: 'options:saveCustom', text: '' }));

// 6. list toggle + pause everywhere
let st = await popup.evaluate(async () => chrome.runtime.sendMessage({ type: 'popup:state', tabId: null }));
check('popup state lists', st.lists.length >= 7 && st.lists.find(l => l.id === 'ads').active, JSON.stringify(st.lists.map(l => l.id + ':' + l.active)));
await popup.evaluate(async () => chrome.runtime.sendMessage({ type: 'popup:togglePause' }));
const enabledWhenPaused = await sw.evaluate(async () => chrome.declarativeNetRequest.getEnabledRulesets());
check('pause everywhere disables all rulesets', enabledWhenPaused.length === 0, JSON.stringify(enabledWhenPaused));
await popup.evaluate(async () => chrome.runtime.sendMessage({ type: 'popup:togglePause' }));
const enabledAfter = await sw.evaluate(async () => chrome.declarativeNetRequest.getEnabledRulesets());
const defaultLists = JSON.parse(fs.readFileSync(path.join(ext, 'filters.json'), 'utf8')).lists.filter(l => l.default).map(l => l.id);
check('resume re-enables default rulesets', enabledAfter.sort().join() === defaultLists.slice().sort().join(), JSON.stringify(enabledAfter));
await popup.evaluate(async () => chrome.runtime.sendMessage({ type: 'popup:toggleList', id: 'cookies' }));
const withCookies = await sw.evaluate(async () => chrome.declarativeNetRequest.getEnabledRulesets());
check('turning on Cookie banners list', withCookies.includes('cookies'), JSON.stringify(withCookies));
await popup.evaluate(async () => chrome.runtime.sendMessage({ type: 'popup:toggleList', id: 'cookies' }));
const scripts = await sw.evaluate(async () => (await chrome.scripting.getRegisteredContentScripts()).map(s => s.id));
check('content scripts registered', scripts.includes('bouclier-cosmetic') && scripts.includes('bouclier-youtube') && scripts.includes('bouclier-css-ads'), JSON.stringify(scripts));

/* ===================== Bouclier 1.1 features ===================== */
const msg = m => popup.evaluate(async x => chrome.runtime.sendMessage(x), m);
const newsTabId = async () => sw.evaluate(async () => (await chrome.tabs.query({})).find(t => (t.url || '').startsWith('http://news.test/')).id);

// F1. welcome / setup check page opened on install
const welcomePages = ctx.pages().filter(p => p.url().includes('/pages/welcome.html'));
check('F1 welcome page opens on first install', welcomePages.length === 1, ctx.pages().map(p => p.url()).join(' | '));
if (welcomePages.length) {
  const w = welcomePages[0];
  await w.waitForSelector('#checks li', { timeout: 5000 }).catch(() => {});
  const items = await w.$$eval('#checks li', lis => lis.map(li => li.className + ':' + li.querySelector('.title').textContent));
  check('F1 setup check lists its checks', items.length >= 4 && items.some(t => t.startsWith('ok:Allowed on every website')), JSON.stringify(items));
}

// F2. per-page breakdown + F3. statistics
await reset();
await page.bringToFront();
await page.goto('http://news.test/', { waitUntil: 'load' });
await page.waitForTimeout(900);
const tabId = await newsTabId();
let pst = await msg({ type: 'popup:state', tabId });
const f2ok = pst.blocked >= 3 && pst.breakdown && pst.breakdown.ads >= 1 && pst.breakdown.trackers >= 1;
check('F2 popup shows what was blocked on the page', f2ok, JSON.stringify({ blocked: pst.blocked, breakdown: pst.breakdown }) + (f2ok ? '' : ' ' + await why(tabId)));
let ost = await msg({ type: 'options:state' });
check('F3 statistics count today\'s blocks', ost.stats.today >= 3 && ost.stats.all >= ost.stats.today, JSON.stringify({ today: ost.stats.today, all: ost.stats.all }));
check('F3 statistics rank sites', ost.stats.topSites.some(([site]) => site === 'news.test'), JSON.stringify(ost.stats.topSites));
check('F3 data-saved estimate is shown', ost.stats.bytesSaved === ost.stats.all * ost.stats.bytesPerBlock);

// F4. link cleaning
await reset();
await page.goto('http://news.test/?utm_source=newsletter&id=5&fbclid=abc123', { waitUntil: 'load' });
await page.waitForTimeout(500);
check('F4 tracking tags removed from the address', page.url() === 'http://news.test/?id=5', page.url());
hits = await log();
check('F4 server never saw the tracking tags', !hits.some(h => /utm_source|fbclid/.test(h)), hits.filter(h => h.includes('news.test/?')).join(' | '));
await page.goto('http://news.test/?next=/a?utm_source=x&ID=1&UTM_SOURCE=y', { waitUntil: 'load' });
check('F4 unusual addresses load unchanged (no redirect loop)', page.url() === 'http://news.test/?next=/a?utm_source=x&ID=1&UTM_SOURCE=y', page.url());

// F5. pop-up blocker
await page.goto('http://news.test/popup', { waitUntil: 'load' });
await page.waitForTimeout(400);
await page.evaluate(() => window.openAd());
await page.waitForTimeout(2500);
const adOpen = ctx.pages().some(p => p.url().includes('11x11.com'));
check('F5 pop-up ad tab closed', !adOpen, ctx.pages().map(p => p.url()).join(' | '));
const toast = await page.evaluate(() => !!document.querySelector('bouclier-toast'));
check('F5 page shows a "pop-up closed" notice', toast);
await page.evaluate(() => window.openOk());
await page.waitForTimeout(1500);
const okPage = ctx.pages().find(p => p.url().includes('docs.test'));
check('F5 normal new tabs stay open', !!okPage, ctx.pages().map(p => p.url()).join(' | '));
if (okPage) await okPage.close();
ost = await msg({ type: 'options:state' });
check('F5 closed pop-ups are counted', (ost.stats.totals.popups || 0) >= 1, JSON.stringify(ost.stats.totals));

// F6. site controls: fonts, third-party scripts, comments
await reset();
await page.goto('http://news.test/', { waitUntil: 'load' });
await page.waitForTimeout(700);
hits = await log();
check('F6 before: font and widget load, comments visible', hits.some(h => h.includes('fonts.test')) && hits.some(h => h.includes('widgets.test')) && (await disp('#comments')) !== 'none', hits.join(' | '));
for (const key of ['fonts', 'scripts3p', 'comments']) await msg({ type: 'popup:siteControl', host: 'news.test', key, value: true });
await reset();
await page.goto('http://news.test/', { waitUntil: 'load' });
await page.waitForTimeout(900);
hits = await log();
check('F6 web fonts blocked on this site', !hits.some(h => h.includes('fonts.test')), hits.join(' | '));
check('F6 third-party scripts blocked on this site', !hits.some(h => h.includes('widgets.test')));
check('F6 first-party scripts still load', hits.some(h => h.includes('cdn.news.test/app.js')));
check('F6 comments hidden on this site', (await disp('#comments')) === 'none');
for (const key of ['fonts', 'scripts3p', 'comments']) await msg({ type: 'popup:siteControl', host: 'news.test', key, value: false });

// F7. timed pauses
await msg({ type: 'popup:pauseSiteFor', host: 'news.test', minutes: 60 });
let settings = (await msg({ type: 'options:state' })).settings;
check('F7 site paused for an hour', settings.pausedSites.includes('news.test') && settings.sitePauseUntil['news.test'] > Date.now() + 59 * 60000, JSON.stringify(settings.sitePauseUntil));
await reset();
await page.goto('http://news.test/', { waitUntil: 'load' });
await page.waitForTimeout(700);
hits = await log();
const f7ok = hits.some(h => h.includes('googletagservices'));
check('F7 ads load while the site is paused', f7ok, f7ok ? '' : await why(await newsTabId()));
const alarms = await sw.evaluate(async () => (await chrome.alarms.getAll()).map(a => a.name));
check('F7 resume alarm scheduled', alarms.includes('resume-site|news.test'), JSON.stringify(alarms));
const purged = await msg({ type: 'debug:purge', now: Date.now() + 61 * 60000 });
check('F7 site resumes when the hour is over', !purged.settings.pausedSites.includes('news.test'), JSON.stringify(purged.settings.pausedSites));
await reset();
await page.goto('http://news.test/', { waitUntil: 'load' });
await page.waitForTimeout(700);
hits = await log();
check('F7 ads blocked again after the timed pause', !hits.some(h => h.includes('googletagservices')));
await msg({ type: 'popup:pauseAllFor', minutes: 30 });
let rs = await sw.evaluate(async () => chrome.declarativeNetRequest.getEnabledRulesets());
check('F7 pause everywhere for 30 minutes', rs.length === 0, JSON.stringify(rs));
await msg({ type: 'debug:purge', now: Date.now() + 31 * 60000 });
rs = await sw.evaluate(async () => chrome.declarativeNetRequest.getEnabledRulesets());
check('F7 everything back on after 30 minutes', rs.length === defaultLists.length, JSON.stringify(rs));

// F8. keyboard shortcuts (command handler)
await page.bringToFront();
await sw.evaluate(async () => runCommand('toggle-site'));
settings = (await msg({ type: 'options:state' })).settings;
check('F8 shortcut pauses the current site', settings.pausedSites.includes('news.test'), JSON.stringify(settings.pausedSites));
await sw.evaluate(async () => runCommand('toggle-site'));
settings = (await msg({ type: 'options:state' })).settings;
check('F8 same shortcut resumes it', !settings.pausedSites.includes('news.test'), JSON.stringify(settings.pausedSites));
const cmds = await sw.evaluate(async () => (await chrome.commands.getAll()).map(c => c.name));
check('F8 shortcuts registered', ['toggle-site', 'hide-element', 'pause-all'].every(c => cmds.includes(c)), JSON.stringify(cmds));

// F9. right-click "Hide This Element…" opens the picker on the clicked element
await page.goto('http://news.test/', { waitUntil: 'load' });
await page.waitForTimeout(500);
await page.click('#real', { button: 'right' });
const tid = await newsTabId();
await sw.evaluate(async id => startPicker(id, { preselect: true, frameId: 0 }), tid);
await page.waitForTimeout(400);
check('F9 right-click picker opens', await page.evaluate(() => !!document.querySelector('bouclier-picker')));
await page.keyboard.press('Escape');
const menuOk = await sw.evaluate(async () => { try { await setupMenus(); await refreshSiteMenu(null); return true; } catch (e) { return String(e); } });
check('F9 right-click menu items created', menuOk === true, String(menuOk));

// F10. hidden elements: count + "show them again"
await msg({ type: 'picker:add', url: 'http://news.test/', selector: '#keep' });
await page.reload({ waitUntil: 'load' });
await page.waitForTimeout(700);
check('F10 picked element hidden', (await disp('#keep')) === 'none');
pst = await msg({ type: 'popup:state', tabId: await newsTabId() });
check('F10 popup counts hidden elements', pst.hiddenCount === 1, String(pst.hiddenCount));
await msg({ type: 'popup:unhideSite', host: 'news.test' });
await page.reload({ waitUntil: 'load' });
await page.waitForTimeout(700);
check('F10 "show them again" restores them', (await disp('#keep')) !== 'none');

// F11. YouTube Shorts toggle
await msg({ type: 'popup:setOption', key: 'youtubeHideShorts', value: true });
await page.goto('http://www.youtube.com/watch?v=abc', { waitUntil: 'load' });
await page.waitForTimeout(900);
check('F11 YouTube Shorts shelf hidden', (await disp('#shorts-shelf')) === 'none');
await msg({ type: 'popup:setOption', key: 'youtubeHideShorts', value: false });
await page.reload({ waitUntil: 'load' });
await page.waitForTimeout(900);
check('F11 Shorts back when switched off', (await disp('#shorts-shelf')) !== 'none');

// F12. backup: export and import
const exp = await msg({ type: 'options:export' });
check('F12 export produces a Bouclier backup', exp.ok && exp.data.format === 'bouclier-settings' && Array.isArray(exp.data.settings.enabledLists));
const imp = await msg({ type: 'options:import', data: { format: 'bouclier-settings', version: 1, settings: {
  pausedSites: ['example.org', 'bad host!'], customFilters: '||tracker.example^', evil: true, showBadge: false,
  pickedHides: { 'example.org': ['.ad', 'x{}'] }, siteControls: { 'lemonde.fr': { fonts: true } }, enabledLists: ['ads', 'nope'] } } });
settings = (await msg({ type: 'options:state' })).settings;
check('F12 import keeps valid settings only', imp.ok && settings.pausedSites.includes('example.org') && !settings.pausedSites.includes('bad host!')
  && settings.customFilters === '||tracker.example^' && !('evil' in settings) && settings.pickedHides['example.org'].length === 1
  && settings.siteControls['lemonde.fr'].fonts === true && settings.enabledLists.join() === 'ads' && settings.showBadge === false, JSON.stringify(settings));
const bad = await msg({ type: 'options:import', data: { hello: 1 } });
check('F12 import refuses other files', bad.ok === false);
// restore the exported settings
await msg({ type: 'options:import', data: exp.data });
settings = (await msg({ type: 'options:state' })).settings;
check('F12 restoring the backup brings everything back', settings.enabledLists.length === exp.data.settings.enabledLists.length && settings.showBadge === true && !settings.pausedSites.includes('example.org'), JSON.stringify(settings));

// F13. badge count switch
await msg({ type: 'options:setOption', key: 'showBadge', value: false });
settings = (await msg({ type: 'options:state' })).settings;
const le = await sw.evaluate(async () => (await chrome.storage.local.get('lastError')).lastError);
check('F13 toolbar count can be switched off', settings.showBadge === false && !le);
await msg({ type: 'options:setOption', key: 'showBadge', value: true });


// F14. "Allow this address on this site" from the popup report
await msg({ type: 'popup:allowHost', host: 'www.googletagservices.com', site: 'news.test' });
await reset();
await page.goto('http://news.test/', { waitUntil: 'load' });
await page.waitForTimeout(800);
hits = await log();
check('F14 allowed address loads on that site', hits.some(h => h.includes('googletagservices')), hits.join(' | '));
check('F14 other blocked addresses stay blocked', !hits.some(h => h.includes('google-analytics')));
settings = (await msg({ type: 'options:state' })).settings;
check('F14 saved as a readable filter', settings.customFilters.includes('@@||www.googletagservices.com^$domain=news.test'), settings.customFilters);
await msg({ type: 'options:saveCustom', text: '' });
await popup.goto(`chrome-extension://${extId}/popup/popup.html`);
await popup.waitForTimeout(600);
const tagButtons = await popup.evaluate(() => {
  state.host = 'news.test'; state.site = 'news.test'; state.paused = false; state.sitePaused = false; state.blocked = 4;
  state.topHosts = [{ host: 'ads.example', n: 3 }, { host: 'pixel.example', n: 1 }];
  renderCount();
  document.querySelector('#detail button.tag').click();
  return { tags: document.querySelectorAll('#detail button.tag').length, confirm: !document.getElementById('allow-confirm').hidden };
});
check('F14 popup lists blocked addresses as buttons (Safari reports addresses)', tagButtons.tags === 2 && tagButtons.confirm, JSON.stringify(tagButtons));


// Review fixes: unsafe selectors refused, timed pauses not exported, pages cannot send privileged messages
const bad2 = await msg({ type: 'options:saveCustom', text: 'news.test##div[class^="ad"\nnews.test##.ok' });
check('R1 incomplete selectors are refused', bad2.errors.length === 1 && /complete CSS/.test(bad2.errors[0]), JSON.stringify(bad2.errors));
await msg({ type: 'options:saveCustom', text: '' });
await msg({ type: 'popup:pauseSiteFor', host: 'timed.example', minutes: 60 });
const exp2 = await msg({ type: 'options:export' });
check('R2 timed pauses are left out of backups', !exp2.data.settings.pausedSites.includes('timed.example') && !('sitePauseUntil' in exp2.data.settings), JSON.stringify(exp2.data.settings.pausedSites));
await msg({ type: 'popup:toggleSite', host: 'timed.example' });
const pageMsg = await page.evaluate(() => new Promise(resolve => {
  // a web page cannot reach the extension directly; this only proves the content-script path stays narrow
  resolve(typeof chrome === 'undefined' || !chrome.runtime || !chrome.runtime.sendMessage ? 'no-api' : 'has-api');
}));
check('R3 web pages have no messaging API into Bouclier', pageMsg === 'no-api', pageMsg);
const csReply = await sw.evaluate(async () => {
  const page = { id: chrome.runtime.id, url: 'http://news.test/', tab: { id: 1 }, frameId: 0 };
  const ext = { id: chrome.runtime.id, url: chrome.runtime.getURL('popup/popup.html') };
  return {
    reset: gateMessage({ type: 'options:reset' }, page) === null ? 'refused' : 'allowed',
    cosmetic: (gateMessage({ type: 'cosmetic', url: 'http://evil.example/' }, page) || {}).url,
    popup: gateMessage({ type: 'options:reset' }, ext) ? 'allowed' : 'refused',
  };
});
check('R3 content scripts cannot trigger settings changes', csReply.reset === 'refused' && csReply.cosmetic === 'http://news.test/' && csReply.popup === 'allowed', JSON.stringify(csReply));

// I. iPhone and iPad (1.2): layout and wording follow the device
const IPHONE_UA = 'Mozilla/5.0 (iPhone; CPU iPhone OS 26_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Mobile/15E148 Safari/604.1';
const IPAD_UA = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15';
const deviceErrors = [];
async function devicePage(ua, width, height) {
  const p = await ctx.newPage();
  p.on('pageerror', e => deviceErrors.push(String(e)));
  p.on('console', m => { if (m.type() === 'error' && !m.text().includes('ERR_BLOCKED_BY_CLIENT')) deviceErrors.push(m.text()); });
  const cdp = await ctx.newCDPSession(p);
  await cdp.send('Emulation.setUserAgentOverride', { userAgent: ua });
  await cdp.send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 2, mobile: width < 700 });
  await cdp.send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 5 });
  // Chromium applies touch emulation to web pages only; extension pages need the touch-point count set by hand
  await p.addInitScript(() => Object.defineProperty(Navigator.prototype, 'maxTouchPoints', { get: () => 5, configurable: true }));
  return { p, cdp };
}
const iphone = await devicePage(IPHONE_UA, 390, 844);
await iphone.p.goto(`chrome-extension://${extId}/popup/popup.html`);
await iphone.p.waitForTimeout(700);
const ip = await iphone.p.evaluate(() => ({
  cls: document.documentElement.className,
  bodyW: Math.round(document.body.getBoundingClientRect().width),
  vw: innerWidth,
  sw: Math.round(document.querySelector('#site-toggle').parentElement.getBoundingClientRect().width),
  over: document.documentElement.scrollWidth - innerWidth,
  lists: document.querySelectorAll('#lists li').length,
}));
check('I1 iPhone menu fills the sheet, with iOS-size switches', ip.cls.includes('ios') && ip.cls.includes('iphone') && ip.bodyW === ip.vw && ip.sw === 51 && ip.over <= 0 && ip.lists > 3, JSON.stringify(ip));
{
  // Playwright's own full-page capture resets the emulated device, so ask DevTools directly
  const h = await iphone.p.evaluate(() => document.documentElement.scrollHeight);
  const shot = await iphone.cdp.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true, clip: { x: 0, y: 0, width: 390, height: h, scale: 1 } });
  fs.writeFileSync(shotPath('popup-iphone.png'), Buffer.from(shot.data, 'base64'));
}

await iphone.p.goto(`chrome-extension://${extId}/options/options.html`);
await iphone.p.waitForTimeout(1200);
const io = await iphone.p.evaluate(() => {
  const shortcuts = [...document.querySelectorAll('section')].find(sec => (sec.querySelector('h2') || {}).textContent === 'Keyboard shortcuts');
  return {
    shortcutsHidden: getComputedStyle(shortcuts).display === 'none',
    macHidden: [...document.querySelectorAll('[data-only="mac"]')].every(n => n.getBoundingClientRect().height === 0),
    iosShown: [...document.querySelectorAll('[data-only="ios"]')].every(n => getComputedStyle(n).display !== 'none'),
    device: document.querySelector('.device').textContent,
    since: document.getElementById('stats-since').textContent,
    over: document.documentElement.scrollWidth - innerWidth,
  };
});
check('I2 iPhone settings hide Mac-only parts and say "this iPhone"', io.shortcutsHidden && io.macHidden && io.iosShown && io.device === 'this iPhone' && io.since.includes('this iPhone') && io.over <= 0, JSON.stringify(io));

const ipad = await devicePage(IPAD_UA, 1024, 1366);
await ipad.p.goto(`chrome-extension://${extId}/pages/welcome.html`);
await ipad.p.waitForTimeout(900);
const iw = await ipad.p.evaluate(() => ({
  cls: document.documentElement.className,
  steps: [...document.querySelectorAll('.steps li')].filter(li => getComputedStyle(li).display !== 'none').map(li => li.textContent.trim().slice(0, 28)),
  checks: document.getElementById('checks').textContent,
}));
check('I3 iPad (desktop-class Safari) gets the iOS steps and Settings paths', iw.cls.includes('ipad') && iw.steps.length === 2 && iw.steps[0].startsWith('Bouclier in Safari') && /Settings → Apps → Safari → Extensions/.test(iw.checks), JSON.stringify(iw));

const macWelcome = await ctx.newPage();
await macWelcome.goto(`chrome-extension://${extId}/pages/welcome.html`);
await macWelcome.waitForTimeout(900);
const mw = await macWelcome.evaluate(() => ({
  cls: document.documentElement.className,
  steps: [...document.querySelectorAll('.steps li')].filter(li => getComputedStyle(li).display !== 'none').length,
  checks: document.getElementById('checks').textContent,
}));
check('I4 Mac keeps the toolbar, right-click and shortcut steps', mw.cls.includes('mac') && !mw.cls.includes('ios') && mw.steps === 3 && /Safari Settings → Extensions/.test(mw.checks), JSON.stringify(mw));
await macWelcome.close();

// I5. the element picker works with taps: a tap chooses, a swipe does not, the bar's buttons still work
const tp = await devicePage(IPHONE_UA, 390, 844);
await tp.p.goto('http://news.test/?touch=1', { waitUntil: 'load' });
await tp.p.waitForTimeout(800);
const touchTab = await sw.evaluate(async () => (await chrome.tabs.query({})).find(t => (t.url || '').includes('?touch=1')).id);
const touch = async (points) => {
  const [first, ...rest] = points;
  await tp.cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [first] });
  for (const pt of rest) await tp.cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [pt] });
  await tp.cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
};
const pickerParts = async () => {
  const { root } = await tp.cdp.send('DOM.getDocument', { depth: -1, pierce: true });
  const found = {};
  const attr = n => (n.attributes || []).reduce((o, v, i, a) => (i % 2 ? o : Object.assign(o, { [v]: a[i + 1] })), {});
  const walk = n => {
    const a = attr(n);
    if (n.nodeName === 'SPAN' && a.class === 'title') found.title = ((n.children || [])[0] || {}).nodeValue;
    if (n.nodeName === 'BUTTON' && /\bhide\b/.test(a.class || '')) found.hide = n.nodeId;
    [...(n.children || []), ...(n.shadowRoots || [])].forEach(walk);
  };
  walk(root);
  return found;
};
await sw.evaluate(async id => startPicker(id, {}), touchTab);
await tp.p.waitForTimeout(400);
const realBox = await tp.p.$eval('#real', e => { const r = e.getBoundingClientRect(); return { x: r.left + 12, y: r.top + r.height / 2 }; });
await touch([realBox, { x: realBox.x, y: realBox.y + 60 }]);
await tp.p.waitForTimeout(250);
const afterSwipe = await pickerParts();
check('I5 a swipe does not choose an element', afterSwipe.title === 'Click the thing you want to hide', String(afterSwipe.title));
await touch([realBox]);
await tp.p.waitForTimeout(300);
const afterTap = await pickerParts();
check('I5 a tap chooses the element under the finger', /^Hide this h1 on news\.test\?$/.test(afterTap.title || ''), String(afterTap.title));
const { model } = await tp.cdp.send('DOM.getBoxModel', { nodeId: afterTap.hide });
const q = model.content;
await touch([{ x: (q[0] + q[2]) / 2, y: (q[1] + q[5]) / 2 }]);
await tp.p.waitForTimeout(600);
const tapHidden = await tp.p.$eval('#real', e => getComputedStyle(e).display);
const tapSaved = (await msg({ type: 'options:state' })).settings.pickedHides['news.test'] || [];
check('I5 tapping Hide hides it and saves it', tapHidden === 'none' && tapSaved.includes('#real'), `${tapHidden} ${JSON.stringify(tapSaved)}`);
await msg({ type: 'popup:unhideSite', host: 'news.test' });
check('I6 no errors on iPhone and iPad pages', deviceErrors.length === 0, deviceErrors.slice(0, 3).join(' | '));
for (const d of [iphone, ipad, tp]) await d.p.close();

const lastError = await sw.evaluate(async () => (await chrome.storage.local.get('lastError')).lastError);
check('no background errors', !lastError, JSON.stringify(lastError));
check('no page console errors', errors.length === 0, errors.slice(0, 5).join(' | '));

// screenshots of popup & options for review
await popup.setViewportSize({ width: 360, height: 900 });
await popup.goto(`chrome-extension://${extId}/popup/popup.html`);
await popup.waitForTimeout(600);
await popup.screenshot({ path: shotPath('popup.png'), fullPage: true });
await popup.emulateMedia({ colorScheme: 'dark' });
await popup.screenshot({ path: shotPath('popup-dark.png'), fullPage: true });
await popup.emulateMedia({ colorScheme: 'light' });
const opt = await ctx.newPage();
await opt.setViewportSize({ width: 1000, height: 900 });
const optErrors = [];
opt.on('console', m => { if (m.type() === 'error') optErrors.push(m.text()); });
opt.on('pageerror', e => optErrors.push(String(e)));
await opt.goto(`chrome-extension://${extId}/options/options.html`);
await opt.waitForTimeout(1200);
check('settings page renders statistics without errors', optErrors.length === 0 && (await opt.$eval('#stat-all', e => e.textContent)) !== '0', optErrors.join(' | '));
await opt.emulateMedia({ colorScheme: 'dark' });
await opt.screenshot({ path: shotPath('options-dark.png'), fullPage: true });
await opt.emulateMedia({ colorScheme: 'light' });
await opt.screenshot({ path: shotPath('options.png'), fullPage: true });

await ctx.close();
const failed = results.filter(r => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
