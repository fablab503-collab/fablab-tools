import { chromium } from '/home/claude/.npm-global/lib/node_modules/playwright/index.mjs';
import fs from 'fs';
const which = process.argv[2];
const profile = fs.mkdtempSync('/tmp/p-');
const ctx = await chromium.launchPersistentContext(profile, { headless: true, channel: 'chromium',
  args: [`--disable-extensions-except=${which}`, `--load-extension=${which}`] });
await new Promise(r => setTimeout(r, 3000));
console.log('workers', ctx.serviceWorkers().map(w => w.url()));
const p = await ctx.newPage();
await p.goto('chrome://extensions');
await p.waitForTimeout(1000);
const txt = await p.evaluate(() => {
  const mgr = document.querySelector('extensions-manager');
  const list = mgr?.shadowRoot?.querySelector('extensions-item-list');
  const items = list?.shadowRoot?.querySelectorAll('extensions-item') || [];
  return [...items].map(i => i.shadowRoot?.textContent?.replace(/\s+/g,' ').slice(0,400));
});
console.log(txt);
await ctx.close();
