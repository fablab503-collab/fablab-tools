// English/French tests (2026-09-30): Bouclier's pages in a browser set to French, then to English.
// French: every text translated (no raw key shows), French lang attribute, a few known French texts,
// nothing wider than a 4.7-inch iPhone. English: the same pages keep their English texts.
//   sudo python3 tests/server.py &   (every host resolves to it on port 80)
//   node tests/i18n-tests.mjs
import { chromium } from '/home/claude/.npm-global/lib/node_modules/playwright/index.mjs';
import path from 'path';
import fs from 'fs';
import os from 'os';

const here = path.dirname(new URL(import.meta.url).pathname);
const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok: !!ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${!ok && detail ? '  — ' + String(detail).slice(0, 400) : ''}`);
};
const IPHONE_UA = 'Mozilla/5.0 (iPhone; CPU iPhone OS 16_7_10 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1';
const RAW_KEY = /\b(popup|options|welcome|bg|picker|list|platform|cmd|ext)_[a-z0-9_]{3,}\b/;

async function browserIn(lang) {
  const ext = fs.mkdtempSync(path.join(os.tmpdir(), `bouclier-i18n-ext-${lang}-`));
  fs.cpSync(path.resolve(here, '../extension'), ext, { recursive: true });
  const ctx = await chromium.launchPersistentContext(fs.mkdtempSync(path.join(os.tmpdir(), `bouclier-i18n-${lang}-`)), {
    headless: true, channel: 'chromium', locale: lang === 'fr' ? 'fr-FR' : 'en-US',
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
    await new Promise(r => setTimeout(r, 500));
  }
  const web = await ctx.newPage();
  await web.goto('http://news.test/', { waitUntil: 'load' });
  const tabId = await sw.evaluate(async () => (await chrome.tabs.query({})).find(t => (t.url || '').startsWith('http://news.test/')).id);
  return { ctx, sw, extId: sw.url().split('/')[2], tabId, web };
}

/** Opens one of Bouclier's pages (as a Mac window or a 4.7-inch iPhone) and reads what it shows. */
async function read(b, page, { iphone = false } = {}) {
  const p = await b.ctx.newPage();
  const cdp = await b.ctx.newCDPSession(p);
  if (iphone) {
    await cdp.send('Emulation.setUserAgentOverride', { userAgent: IPHONE_UA });
    await cdp.send('Emulation.setDeviceMetricsOverride', { width: 375, height: 667, deviceScaleFactor: 2, mobile: true });
    await p.addInitScript(() => Object.defineProperty(Navigator.prototype, 'maxTouchPoints', { get: () => 5, configurable: true }));
  } else {
    await p.setViewportSize({ width: page.includes('popup') ? 360 : 1100, height: 900 });
  }
  await p.goto(`chrome-extension://${b.extId}/${page}`);
  await p.waitForTimeout(900);
  if (page.includes('popup')) {
    await p.evaluate(async id => { tab = await chrome.tabs.get(id); await refresh(); }, b.tabId);
    await p.waitForTimeout(400);
  }
  const out = await p.evaluate(() => {
    const texts = [];
    const walk = n => {
      for (const c of n.childNodes) {
        if (c.nodeType === 3) {
          const s = c.textContent.trim();
          const el = c.parentElement;
          if (s && el && el.offsetParent !== null) texts.push(s);
        } else if (c.nodeType === 1 && !['SCRIPT', 'STYLE'].includes(c.tagName)) walk(c);
      }
    };
    walk(document.body);
    const attrs = [...document.querySelectorAll('[aria-label],[title],[placeholder]')]
      .map(e => e.getAttribute('aria-label') || e.getAttribute('title') || e.getAttribute('placeholder'));
    return { lang: document.documentElement.lang, title: document.title, texts: texts.concat(attrs.filter(Boolean)), overflow: document.documentElement.scrollWidth - innerWidth };
  });
  await p.close();
  return out;
}

const PAGES = ['popup/popup.html', 'options/options.html', 'pages/welcome.html'];

// French
{
  const b = await browserIn('fr');
  const ui = await b.sw.evaluate(() => chrome.i18n.getUILanguage());
  check('F0 the test browser runs in French', /^fr/.test(ui), ui);
  const want = {
    'popup/popup.html': ['Ce site', 'Mettre en pause sur ce site pendant', '1 heure', '2 heures', 'Jusqu’à demain', 'Masquer un élément', 'Filtres'],
    'options/options.html': ['Réglages de Bouclier'],
    'pages/welcome.html': ['Bouclier est activé', 'Vérification de l’installation'],
  };
  for (const page of PAGES) {
    for (const iphone of [false, true]) {
      const r = await read(b, page, { iphone });
      const where = `${page.split('/')[1]} (${iphone ? 'iPhone 8' : 'Mac'})`;
      const raw = r.texts.filter(s => RAW_KEY.test(s));
      const missing = (want[page] || []).filter(w => !r.texts.some(s => s.includes(w)) && !r.title.includes(w));
      check(`F1 ${where}: French, every text translated`, r.lang === 'fr' && raw.length === 0 && missing.length === 0, JSON.stringify({ lang: r.lang, raw, missing }));
      if (iphone) check(`F2 ${where}: nothing wider than the screen in French`, r.overflow <= 0, `overflow ${r.overflow}`);
    }
  }
  const menus = await b.sw.evaluate(() => t('bg_menu_hide'));
  check('F3 context menu titles in French', menus === 'Masquer cet élément…', menus);
  const cannot = await b.sw.evaluate(() => cannotPause());
  check('F4 background messages in French', /Bouclier ne peut pas/.test(cannot), cannot);
  await b.ctx.close();
}

// English
{
  const b = await browserIn('en');
  const want = {
    'popup/popup.html': ['This site', 'Pause on this site for', '1 hour', '2 hours', 'Until tomorrow', 'Hide an element', 'Filters'],
    'options/options.html': ['Bouclier settings'],
    'pages/welcome.html': ['Bouclier is on', 'Setup check'],
  };
  for (const page of PAGES) {
    const r = await read(b, page);
    const raw = r.texts.filter(s => RAW_KEY.test(s));
    const missing = want[page].filter(w => !r.texts.some(s => s.includes(w)) && !r.title.includes(w));
    check(`E1 ${page.split('/')[1]}: English by default`, r.lang === 'en' && raw.length === 0 && missing.length === 0, JSON.stringify({ lang: r.lang, raw, missing }));
  }
  await b.ctx.close();
}

const failed = results.filter(r => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
