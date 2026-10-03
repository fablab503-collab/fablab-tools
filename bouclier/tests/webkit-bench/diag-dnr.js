// Timed extension calls, run inside Bouclier's menu by `PopupShot --eval "$(cat diag-dnr.js)"`:
// how long WebKit takes to change the rules (a site pause adds one rule, and WebKit then compiles
// every rule of the extension again), and how long Bouclier's own site pause takes.
const now = () => performance.now();
const within = (p, ms) => Promise.race([p, new Promise(r => setTimeout(() => r('still waiting after ' + ms / 1000 + ' s'), ms))]);
const limit = 900000;
const out = {};
let t0 = now();
out.dynamicRules = (await browser.declarativeNetRequest.getDynamicRules()).length;
out.getMs = Math.round(now() - t0);
t0 = now();
out.addOneRule = await within(browser.declarativeNetRequest.updateDynamicRules({
  addRules: [{ id: 990001, priority: 1, action: { type: 'allowAllRequests' }, condition: { requestDomains: ['example.org'], resourceTypes: ['main_frame'] } }],
}).then(() => 'ok', e => 'error: ' + e.message), limit);
out.addOneRuleMs = Math.round(now() - t0);
t0 = now();
out.removeIt = await within(browser.declarativeNetRequest.updateDynamicRules({ removeRuleIds: [990001] }).then(() => 'ok', e => 'error: ' + e.message), limit);
out.removeItMs = Math.round(now() - t0);
t0 = now();
out.pauseSite = await within(browser.runtime.sendMessage({ type: 'popup:pauseSiteFor', host: 'example.org', minutes: 60 }).then(r => JSON.stringify(r), e => 'error: ' + e.message), limit);
out.pauseSiteMs = Math.round(now() - t0);
return JSON.stringify(out);
