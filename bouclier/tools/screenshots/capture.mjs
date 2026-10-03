// Captures Bouclier's real screens for the App Store screenshots and the official page's device
// images: the demo news site with Bouclier on and paused, the menu for that page (Mac, iPhone and
// iPad layouts), the element picker and the statistics. Runs headless Chromium with the extension
// loaded (the same UI Safari shows; the iPhone/iPad layouts come from the user agent), in English
// or in French (Bouclier follows the browser's language; the French demo site is "Le Quotidien
// Exemple" at quotidien.example). Output: tools/screenshots/raw/<lang>/*.png
//
// usage: node tools/screenshots/capture.mjs [--lang en|fr]   (Playwright; starts demo_server.py on :80)
import { execSync, spawn } from 'child_process';
import path from 'path';
import fs from 'fs';
import os from 'os';
// Playwright from the project, or from the global npm folder
const { chromium } = await import('playwright').catch(() => {
  const root = execSync('npm root -g').toString().trim();
  return import(root + '/playwright/index.mjs');
});

const argv = process.argv.slice(2);
const LANG = argv.includes('--lang') ? argv[argv.indexOf('--lang') + 1] : 'en';
if (!['en', 'fr'].includes(LANG)) throw new Error(`--lang en or fr, not ${LANG}`);
const FR = LANG === 'fr';

const HERE = path.dirname(new URL(import.meta.url).pathname);
const REPO = path.resolve(HERE, '../..');
const RAW = path.join(HERE, 'raw', LANG);
fs.mkdirSync(RAW, { recursive: true });
const HOST = FR ? 'quotidien.example' : 'daily.example';
const DEMO = `http://${HOST}/`;
const IPHONE_UA = 'Mozilla/5.0 (iPhone; CPU iPhone OS 26_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Mobile/15E148 Safari/604.1';
// Safari on a Mac; an iPad asks for desktop sites and sends the same, with a touch screen
const MAC_UA = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15';
const IPAD_UA = MAC_UA;

const server = spawn('python3', [path.join(HERE, 'demo_server.py'), '80'], { stdio: 'inherit' });
await new Promise(r => setTimeout(r, 800));

const ext = fs.mkdtempSync(path.join(os.tmpdir(), 'bouclier-shots-ext-'));
fs.cpSync(path.join(REPO, 'extension'), ext, { recursive: true });
const ctx = await chromium.launchPersistentContext(fs.mkdtempSync(path.join(os.tmpdir(), 'bouclier-shots-profile-')), {
  headless: true, channel: 'chromium', deviceScaleFactor: 2, locale: FR ? 'fr-FR' : 'en-US',
  args: [`--disable-extensions-except=${ext}`, `--load-extension=${ext}`, `--lang=${LANG}`,
    '--host-resolver-rules=MAP * 127.0.0.1, EXCLUDE localhost', '--no-proxy-server'],
  // Chromium on Linux takes its interface language (Bouclier's language) from these
  env: { ...process.env, LANG: FR ? 'fr_FR.UTF-8' : 'en_US.UTF-8', LANGUAGE: LANG },
});
let [sw] = ctx.serviceWorkers();
if (!sw) sw = await ctx.waitForEvent('serviceworker', { timeout: 15000 });
const extId = sw.url().split('/')[2];
for (let i = 0; i < 60; i++) {
  let st = null;
  try { st = await sw.evaluate(async () => (await chrome.storage.local.get('state')).state); } catch {}
  if (st) break;
  await new Promise(r => setTimeout(r, 500));
}
const bg = (fn, arg) => sw.evaluate(fn, arg);
const uiLang = await bg(() => chrome.i18n.getUILanguage());
if (!uiLang.startsWith(LANG)) throw new Error(`the browser runs in ${uiLang}, not ${LANG}`);
console.log(`capturing in ${uiLang}, demo site ${HOST}`);

// A page with its own device: size, pixel ratio, user agent, touch.
async function devicePage({ width, height, scale, ua, mobile = false, touch = false }) {
  const p = await ctx.newPage();
  const cdp = await ctx.newCDPSession(p);
  if (ua) await cdp.send('Emulation.setUserAgentOverride', { userAgent: ua, acceptLanguage: FR ? 'fr-FR,fr' : 'en-US,en' });
  await cdp.send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: scale, mobile });
  if (touch) {
    await cdp.send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 5 });
    await p.addInitScript(() => Object.defineProperty(Navigator.prototype, 'maxTouchPoints', { get: () => 5, configurable: true }));
  }
  const shot = async (name, full = false) => {
    const h = full ? await p.evaluate(() => document.documentElement.scrollHeight) : height;
    const r = await cdp.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: full, clip: { x: 0, y: 0, width, height: h, scale: 1 } });
    fs.writeFileSync(path.join(RAW, name + '.png'), Buffer.from(r.data, 'base64'));
    console.log(`  raw/${LANG}/${name}.png`);
  };
  return { p, cdp, shot };
}

// popup messages go through a helper page (the popup's own channel)
const helper = await ctx.newPage();
await helper.goto(`chrome-extension://${extId}/popup/popup.html`);
const msg = m => helper.evaluate(x => chrome.runtime.sendMessage(x), m);

// Page sizes = the web area of the device frames drawn by compose.py (screen minus status bar
// and Safari's bars), so each capture fills its frame exactly.
const DEVICES = {
  mac: { width: 1440, height: 1000, scale: 2, ua: MAC_UA },
  ipad: { width: 1032, height: 1276, scale: 2, ua: IPAD_UA, touch: true },
  iphone: { width: 440, height: 846, scale: 3, ua: IPHONE_UA, mobile: true, touch: true },
};

// 1. the demo page, protected and paused, on each device
for (const [name, dev] of Object.entries(DEVICES)) {
  const d = await devicePage(dev);
  await d.p.goto(DEMO, { waitUntil: 'load' });
  await d.p.waitForTimeout(1500);
  await d.shot(`${name}-page-on`);
  await msg({ type: 'popup:toggleSite', host: HOST });   // pause this site
  await d.p.reload({ waitUntil: 'load' });
  await d.p.waitForTimeout(1200);
  await d.shot(`${name}-page-off`);
  await msg({ type: 'popup:toggleSite', host: HOST });   // protect it again
  await d.p.reload({ waitUntil: 'load' });
  await d.p.waitForTimeout(1500);
  d.name = name;
  DEVICES[name].page = d;
}

// 2. Bouclier's menu for that page (the popup believes the demo tab is the active one)
const demoTab = await bg(async url => (await chrome.tabs.query({ url }))[0].id, `${DEMO}*`);
async function openMenu(dev) {
  const d = await devicePage(dev);
  await d.p.addInitScript(id => {
    const q = chrome.tabs.query.bind(chrome.tabs);
    chrome.tabs.query = async info => (info && info.active) ? [await chrome.tabs.get(id)] : q(info);
  }, demoTab);
  await d.p.goto(`chrome-extension://${extId}/popup/popup.html`);
  await d.p.waitForTimeout(1500);
  return d;
}
// where things are, for the arrows and rings drawn by compose.py (CSS pixels of the capture)
const menuSpots = d => d.p.evaluate(() => {
  const rect = e => { if (!e) return null; const r = e.getBoundingClientRect(); return { x: r.x, y: r.y, w: r.width, h: r.height }; };
  const at = sel => rect(document.querySelector(sel));
  const card = at('#site-card');
  return {
    width: innerWidth, height: document.documentElement.scrollHeight, bottom: card ? card.y + card.h + 12 : null,
    toggle: at('label.switch.big'), pause: at('#site-pause'), chips: [...document.querySelectorAll('#site-pause .chip')].map(rect),
    pick: at('#pick'), count: at('#count'), filters: at('section.card:not(#site-card):not(#pause-card)'), more: at('#more > summary'),
  };
});
for (const [name, dev] of [['mac', { width: 360, height: 700, scale: 3 }], ['iphone', { width: 440, height: 956, scale: 3, ua: IPHONE_UA, mobile: true, touch: true }]]) {
  const d = await openMenu(dev);
  // the screenshots show the part about this page; the filter list stays in the app
  await d.p.addStyleTag({ content: 'section.card:not(#site-card), .banner, footer, .foot { display: none !important; }' });
  await d.p.waitForTimeout(300);
  await d.shot(`${name}-menu`, true);
  fs.writeFileSync(path.join(RAW, `${name}-menu.json`), JSON.stringify(await menuSpots(d), null, 1));
  // the "Pause everywhere" card on its own
  await d.p.addStyleTag({ content: 'header.top, #site-card { display: none !important; } section.card#pause-card { display: block !important; margin-top: 12px; }' });
  await d.p.waitForTimeout(300);
  await d.shot(`${name}-pausecard`, true);
  const pc = await d.p.evaluate(() => { const r = document.querySelector('#pause-card').getBoundingClientRect(); return { x: r.x, y: r.y, w: r.width, h: r.height, width: innerWidth }; });
  fs.writeFileSync(path.join(RAW, `${name}-pausecard.json`), JSON.stringify(pc));
  await d.p.close();
}

// 3. the element picker on each device's page, with the newsletter box chosen
const demoTabs = await bg(async url => (await chrome.tabs.query({ url })).map(t => t.id), `${DEMO}*`);
for (const id of demoTabs) await msg({ type: 'popup:picker', tabId: id }).catch(() => {});
for (const [name, dev] of Object.entries(DEVICES)) {
  const d = dev.page;
  await d.p.evaluate(() => window.scrollTo(0, 0));
  await d.p.waitForTimeout(600);
  const box = await d.p.evaluate(() => {
    const r = document.querySelector('.briefing').getBoundingClientRect();
    return { x: r.x + 8, y: r.y + 6 };  // the padding, so the whole box is chosen
  });
  await d.p.mouse.move(box.x, box.y);
  await d.p.waitForTimeout(300);
  await d.p.mouse.click(box.x, box.y);
  await d.p.waitForTimeout(700);
  await d.shot(`${name}-picker`);
  await d.p.keyboard.press('Escape');
}

// 4. statistics: two weeks of sample numbers (the demo browser is fresh)
await bg(host => {
  const day = n => { const d = new Date(Date.now() - n * 86400000); return d.toISOString().slice(0, 10); };
  const days = {};
  const base = [1180, 1420, 960, 1610, 1890, 1340, 1120, 1530, 1760, 1290, 1650, 2010, 1470, 1380];
  base.forEach((n, i) => { days[day(13 - i)] = { ads: Math.round(n * 0.56), trackers: Math.round(n * 0.41), malware: i % 5 === 0 ? 3 : 1, links: 14 + (i % 4) * 3, popups: i % 3 }; });
  const others = host === 'quotidien.example'
    ? ['recettes.example', 'meteo.example', 'sport.example', 'boutique.example', 'video.example']
    : ['recipes.example', 'weather.example', 'sport.example', 'shop.example', 'video.example'];
  const sites = { [host]: 4210 };
  [2890, 2240, 1970, 1210, 980].forEach((n, i) => { sites[others[i]] = n; });
  const domains = { 'doubleclick.net': 3920, 'googlesyndication.com': 2410, 'google-analytics.com': 2130, 'criteo.com': 1180, 'taboola.com': 1090, 'amazon-adsystem.com': 870, 'scorecardresearch.com': 760 };
  return chrome.storage.local.set({ stats: { since: Date.now() - 41 * 86400000, days, sites, domains } });
}, HOST);
for (const [name, dev] of [['iphone', DEVICES.iphone], ['ipad', DEVICES.ipad]]) {
  const d = await devicePage(dev);
  await d.p.goto(`chrome-extension://${extId}/options/options.html`);
  await d.p.waitForTimeout(1500);
  await d.p.evaluate(() => { const s = document.querySelector('#stats-section'); window.scrollTo(0, s.getBoundingClientRect().top + scrollY - 12); });
  await d.p.waitForTimeout(400);
  await d.shot(`${name}-stats`);
  const rect = await d.p.evaluate(() => { const r = document.querySelector('#stats-section').getBoundingClientRect(); return { x: r.x, y: r.y, w: r.width, h: r.height, width: innerWidth, height: innerHeight }; });
  fs.writeFileSync(path.join(RAW, `${name}-stats.json`), JSON.stringify(rect));
  await d.p.close();
}
{
  const d = await devicePage({ width: 1100, height: 900, scale: 2, ua: MAC_UA });
  await d.p.goto(`chrome-extension://${extId}/options/options.html`);
  await d.p.waitForTimeout(1500);
  const r = await d.p.evaluate(() => { const s = document.querySelector('#stats-section'); s.scrollIntoView(); const b = s.getBoundingClientRect(); return { y: b.y, h: b.height }; });
  await d.p.waitForTimeout(300);
  const shot = await d.cdp.send('Page.captureScreenshot', { format: 'png', clip: { x: 0, y: Math.max(0, r.y - 16), width: 1100, height: Math.min(900, r.h + 32), scale: 1 } });
  fs.writeFileSync(path.join(RAW, 'stats.png'), Buffer.from(shot.data, 'base64'));
  console.log(`  raw/${LANG}/stats.png`);
}

// 5. the official page's device images (site_screens.py): the page and the whole menu at the
// native screen sizes of Apple's MacBook Pro 14" (looks like 1352 x 878), iPad Pro 13" and
// iPhone 17 Pro. The menu is shown as it is, nothing hidden.
const SITE = {
  mac: { page: { width: 1352, height: 878, scale: 3024 / 1352, ua: MAC_UA }, menu: { width: 330, height: 700, scale: 3024 / 1352, ua: MAC_UA } },
  ipad: { page: { width: 1032, height: 1376, scale: 2, ua: IPAD_UA, touch: true }, menu: { width: 360, height: 700, scale: 2, ua: IPAD_UA, touch: true } },
  iphone: { page: { width: 402, height: 874, scale: 3, ua: IPHONE_UA, mobile: true, touch: true }, menu: { width: 402, height: 874, scale: 3, ua: IPHONE_UA, mobile: true, touch: true } },
};
for (const [name, { page, menu }] of Object.entries(SITE)) {
  const d = await devicePage(page);
  await d.p.goto(DEMO, { waitUntil: 'load' });
  await d.p.waitForTimeout(1500);
  await d.shot(`site-${name}-page`);
  await d.p.close();
  const m = await openMenu(menu);
  await m.p.waitForTimeout(300);
  await m.shot(`site-${name}-menu`, true);
  fs.writeFileSync(path.join(RAW, `site-${name}-menu.json`), JSON.stringify(await menuSpots(m), null, 1));
  await m.p.close();
}

await ctx.close();
server.kill();
