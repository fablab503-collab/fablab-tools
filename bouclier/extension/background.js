/*
 * Bouclier – background service worker.
 *
 * Owns every piece of state and keeps Safari in sync with it:
 *   - which filter lists (declarativeNetRequest static rulesets) are on,
 *   - dynamic rules: paused sites, the user's own filters, per-site controls,
 *   - registered content scripts: generic element hiding (user stylesheets),
 *     the page helper, and the YouTube script,
 *   - site-specific element hiding, injected on demand as user stylesheets,
 *   - timed pauses (alarms), blocking statistics, the pop-up blocker,
 *     the right-click menu and keyboard shortcuts.
 *
 * Safari compiles all declarativeNetRequest rules of an extension into one
 * WebKit content rule list capped at 150,000 rules, so every change is checked
 * against that budget before it is applied.
 */
'use strict';

const api = globalThis.browser ?? globalThis.chrome;
const menus = api.contextMenus || api.menus || null;

/*
 * What people see from here (the right-click menu, notices shown on pages, messages for the menu and
 * the settings page) is in the device's language: French on a device set to French, English
 * otherwise. The texts are in i18n/background.json (tools/i18n.py builds _locales from it).
 * common/i18n.js touches the page, so the worker has its own t(), which works the same way:
 * t(key, a, b) is the text with {0}, {1}… replaced by a, b (a missing text shows its key).
 */
function t(key, ...subs) {
  let s = '';
  try { s = api.i18n.getMessage(key) || ''; } catch { /* no i18n */ }
  if (!s) return key;
  return subs.length ? s.replace(/\{(\d)\}/g, (m, i) => (subs[i] !== undefined ? String(subs[i]) : m)) : s;
}

/** A number as the device writes it: "5 000" in French; in English the device's own format ("5,000"). */
function formatNumber(n) {
  let lang;
  try { if (/^fr(?![a-z])/i.test(api.i18n.getUILanguage() || '')) lang = 'fr'; } catch { /* English */ }
  return n.toLocaleString(lang);
}

const WEBKIT_BUDGET = 147000;           // Safari's hard cap is 150,000
const PRIORITY_SITE_ALLOW = 100;        // beats every list rule
const PRIORITY_SITE_CONTROL = 5;        // per-site "block fonts / scripts"
const PRIORITY_CUSTOM_BLOCK = 10;       // beats list allow rules (2) and $important (3)
const PRIORITY_CUSTOM_ALLOW = 11;
// Dynamic rule ids are grouped so statistics can tell allow rules from blocks.
const ID_SITE_PAUSE = 1;                // 1..9999      allowAllRequests for paused sites
const ID_CUSTOM_ALLOW = 10000;          // 10000..19999 the user's @@ filters
const ID_CUSTOM_BLOCK = 20000;          // 20000..29999 the user's blocking filters
const ID_SITE_CONTROL = 30000;          // 30000..      per-site controls
const CS_PREFIX = 'bouclier-';
const YT_MATCHES = [
  '*://youtube.com/*', '*://*.youtube.com/*',
  '*://youtube-nocookie.com/*', '*://*.youtube-nocookie.com/*',
  '*://youtubekids.com/*', '*://*.youtubekids.com/*',
];
const SAFARI_TYPES = ['main_frame', 'sub_frame', 'stylesheet', 'script', 'image', 'font',
  'xmlhttprequest', 'ping', 'media', 'websocket', 'other'];
const BYTES_PER_BLOCK = 20 * 1024;      // rough average used for the "data saved" estimate

const COMMENT_SELECTORS = [
  '#comments', '.comments', '#comment-section', '.comment-section', '.comments-section',
  '.comments-area', '#comments-area', '.comment-list', '.commentlist', '#respond',
  '#disqus_thread', '.fb-comments', '#coral_thread', '.coral-talk-stream', '[id^="comments-"]',
  '.js-comments', '.article-comments', '#article-comments', 'section[aria-label="Comments"]',
  'ytd-comments', 'ytm-comment-section-renderer',
];

const DEFAULT_SETTINGS = {
  schema: 2,
  paused: false,            // "pause everywhere"
  pausedUntil: 0,           // when a timed global pause ends (0 = until turned back on)
  enabledLists: null,       // null -> the catalogue's defaults
  youtube: true,            // YouTube ad removal
  youtubeHideShorts: false, // hide Shorts shelves and links on YouTube
  pausedSites: [],          // hostnames where protection is off
  sitePauseUntil: {},       // hostname -> when a timed site pause ends
  customFilters: '',        // the user's own filters, one per line
  pickedHides: {},          // hostname -> [css selectors] from "Hide an element"
  siteControls: {},         // hostname -> { fonts, scripts3p, comments }
  popupBlocker: true,       // close pop-up / pop-under ad tabs
  showBadge: true,          // blocked count on the toolbar icon
};

const CATEGORY_OF_LIST = {
  ads: 'ads', french: 'ads', antiadblock: 'ads', privacy: 'trackers', safety: 'malware',
  urlclean: 'links', cookies: 'annoyances', social: 'annoyances', annoyances: 'annoyances',
};
/** What the menu and the settings page call each kind of blocked load, in the device's language. */
function categoryLabels() {
  return {
    ads: t('bg_category_ads'), trackers: t('bg_category_trackers'), malware: t('bg_category_malware'),
    annoyances: t('bg_category_annoyances'), custom: t('bg_category_custom'), site: t('bg_category_site'),
    links: t('bg_category_links'), popups: t('bg_category_popups'), other: t('bg_category_other'),
    blocked: t('bg_category_blocked'),
  };
}

/* ------------------------------------------------------------------ utils */

let catalogueCache = null;
async function getCatalogue() {
  if (!catalogueCache) {
    const res = await fetch(api.runtime.getURL('filters.json'));
    catalogueCache = await res.json();
  }
  return catalogueCache;
}

// A selector that matches the whole page would blank every site it applies to.
const WHOLE_PAGE_SELECTOR = /^\s*(\*|html|body|:root)(\s*>?\s*(body|\*))?\s*$/i;

/** Rejects selectors that could break the stylesheet they are injected into, or hide the whole page. */
function isSafeSelector(sel) {
  if (typeof sel !== 'string' || !sel.trim() || sel.length > 1000) return false;
  if (/[{}]|\/\*|\*\/|\\$/.test(sel)) return false;
  const stack = [];
  let quote = null;
  for (let i = 0; i < sel.length; i++) {
    const ch = sel[i];
    if (quote) {
      if (ch === '\\') i++;
      else if (ch === quote) quote = null;
      continue;
    }
    if (ch === '"' || ch === "'") quote = ch;
    else if (ch === '\\') i++;
    else if (ch === ';' || ch === '@') return false;     // would end the rule or start an at-rule
    else if (ch === '[' || ch === '(') stack.push(ch === '[' ? ']' : ')');
    else if (ch === ']' || ch === ')') { if (stack.pop() !== ch) return false; }
  }
  if (quote || stack.length) return false;
  return !sel.split(',').some(part => WHOLE_PAGE_SELECTOR.test(part));
}

function isHostname(value) {
  return typeof value === 'string' && /^[a-z0-9.-]{1,253}$/.test(value) && !value.startsWith('.');
}

async function getSettings() {
  const { settings } = await api.storage.local.get('settings');
  const s = Object.assign({}, DEFAULT_SETTINGS, settings || {});
  if (!Array.isArray(s.enabledLists)) {
    const cat = await getCatalogue();
    s.enabledLists = cat.lists.filter(l => l.default).map(l => l.id);
  } else if (settings && (settings.schema || 1) < 2 && !s.enabledLists.includes('urlclean')) {
    // "Clean links" arrived in version 1.1 and is on by default
    s.enabledLists = s.enabledLists.concat('urlclean');
  }
  // a list id twice would register its content script twice, which Safari refuses
  s.enabledLists = [...new Set(s.enabledLists.filter(id => typeof id === 'string'))];
  if (!Array.isArray(s.pausedSites)) s.pausedSites = [];
  if (typeof s.customFilters !== 'string') s.customFilters = '';
  for (const key of ['pickedHides', 'siteControls', 'sitePauseUntil']) {
    if (!s[key] || typeof s[key] !== 'object' || Array.isArray(s[key])) s[key] = {};
  }
  s.schema = 2;
  return s;
}

async function saveSettings(s) {
  await api.storage.local.set({ settings: s });
}

function hostnameOf(url) {
  try {
    const u = new URL(url);
    if (u.protocol !== 'http:' && u.protocol !== 'https:') return null;
    return u.hostname.toLowerCase().replace(/\.$/, '');
  } catch {
    return null;
  }
}

// "www.gov.uk" must stay itself: without "www." it would be a public suffix shared by many sites.
const PUBLIC_SECOND_LEVEL = /^(?:co|com|net|org|gov|gouv|edu|ac|nhs|police|ltd|plc|sch|ne|or|go|mil|nic|asso)\.[a-z]{2}$/;

/** The hostname we store for a site-level choice. */
function siteKey(host) {
  if (!host) return host;
  const rest = host.replace(/^www\d*\./, '');
  if (rest === host || !rest.includes('.') || PUBLIC_SECOND_LEVEL.test(rest)) return host;
  return rest;
}

function isSitePaused(host, pausedSites) {
  if (!host) return false;
  return pausedSites.some(site => host === site || host.endsWith('.' + site));
}

function pausedSiteFor(host, pausedSites) {
  if (!host) return null;
  return pausedSites.find(site => host === site || host.endsWith('.' + site)) || null;
}

function sitePatterns(sites) {
  const out = [];
  for (const site of sites || []) {
    if (!isHostname(site)) continue;
    out.push(`*://${site}/*`, `*://*.${site}/*`);
  }
  return out;
}

/** All keys a cosmetic rule for this host could be filed under. */
function hostCandidates(host) {
  const parts = host.split('.');
  const out = [];
  for (let i = 0; i < parts.length - 1; i++) out.push(parts.slice(i).join('.'));
  if (parts.length === 1) out.push(host);
  for (let i = 0; i < parts.length - 1; i++) {
    const rest = parts.slice(i);
    for (let k = 1; k <= 2 && k < rest.length; k++) {
      out.push(rest.slice(0, rest.length - k).join('.') + '.*');
    }
  }
  return out;
}

/** Rough registrable domain ("news.bbc.co.uk" -> "bbc.co.uk"), enough for first/third-party checks. */
function baseDomain(host) {
  if (!host) return host;
  if (/^\d+(\.\d+){3}$/.test(host)) return host;
  const parts = host.split('.');
  if (parts.length <= 2) return host;
  const sld = parts[parts.length - 2];
  const tld = parts[parts.length - 1];
  const take = tld.length === 2 && sld.length <= 3 ? 3 : 2;
  return parts.slice(-take).join('.');
}

function hostMatches(host, domain) {
  return host === domain || host.endsWith('.' + domain);
}

/** WebKit rules Safari generates for one DNR rule (mirrors tools/convert.py). */
function webkitCount(rule) {
  const c = rule.condition || {};
  const actionType = rule.action.type;
  const allowAll = actionType === 'allowAllRequests';
  const rd = c.regexFilter ? null : c.requestDomains;
  const rm = allowAll ? null : c.requestMethods;
  const mainUnits = (rd ? rd.length : 1) * (rm ? rm.length : 1);
  const erd = c.excludedRequestDomains;
  const erm = allowAll ? null : c.excludedRequestMethods;
  const exclUnits = erd ? erd.length * (erm ? erm.length : 1) : (erm ? erm.length : 0);
  let perUnit = 1;
  if (c.initiatorDomains && c.excludedInitiatorDomains && !allowAll) perUnit += 1;
  if (actionType === 'upgradeScheme') perUnit += 1;
  return (mainUnits + exclUnits) * perUnit;
}

function dayKey(ts = Date.now()) {
  const d = new Date(ts);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

/** storage.session when available (Safari 16.4+), otherwise memory. */
const memorySession = {};
const session = {
  async get(key) {
    if (api.storage.session) {
      try {
        const out = await api.storage.session.get(key);
        return out[key];
      } catch { /* fall through */ }
    }
    return memorySession[key];
  },
  async set(key, value) {
    memorySession[key] = value;
    if (api.storage.session) {
      try { await api.storage.session.set({ [key]: value }); } catch { /* memory only */ }
    }
  },
};

/* ------------------------------------------------- the user's own filters */

const TYPE_OPTIONS = {
  script: 'script', image: 'image', stylesheet: 'stylesheet', css: 'stylesheet',
  xmlhttprequest: 'xmlhttprequest', xhr: 'xmlhttprequest', subdocument: 'sub_frame',
  frame: 'sub_frame', ping: 'ping', websocket: 'websocket', media: 'media', font: 'font',
  other: 'other', document: 'main_frame', doc: 'main_frame',
};

function parseDomainOption(value) {
  const inc = [];
  const exc = [];
  for (let d of value.split('|')) {
    d = d.trim().toLowerCase();
    if (!d) continue;
    const neg = d.startsWith('~');
    if (neg) d = d.slice(1);
    if (!/^[a-z0-9.-]+$/.test(d)) return null;
    (neg ? exc : inc).push(d);
  }
  return { inc, exc };
}

/**
 * Parses the user's filters (Adblock Plus syntax subset):
 *   example.com                block everything from example.com
 *   ||ads.example.com^         same, explicit
 *   /banner/*.gif$image        URL pattern with options
 *   @@||example.com^           never block example.com
 *   example.com##.promo        hide .promo on example.com
 *   ##.sponsored               hide .sponsored everywhere
 *   example.com#@#.promo       don't hide .promo on example.com
 */
/** A site written in front of ## -> the hostname pages report ("bücher.de" -> "xn--bcher-kva.de"), or null. */
function cosmeticHost(d) {
  const neg = d.startsWith('~') ? '~' : '';
  const h = d.slice(neg.length);
  if (/^[a-z0-9-]+(\.[a-z0-9-]+)*\.\*$/.test(h)) return d;           // example.* (any ending)
  if (!h || /[:/\s\\@?#]/.test(h)) return null;                          // ports, paths, spaces
  try {
    const host = new URL(`http://${h}/`).hostname.replace(/\.$/, '');
    return isHostname(host) ? neg + host : null;
  } catch {
    return null;
  }
}

function parseCustomFilters(text) {
  const network = [];
  const cosmetic = { specific: {}, generic: [], genericExcluded: {}, exceptions: {} };
  const errors = [];
  const lines = String(text || '').split(/\r?\n/);
  lines.forEach((raw, index) => {
    let line = raw.trim();
    // comments: "! …" (Adblock Plus), "[Adblock Plus 2.0]" headers, "# …" (hosts files)
    if (!line || line.startsWith('!') || line.startsWith('[') || /^#(\s|$)/.test(line)) return;
    const lineNo = index + 1;   // the messages start with it: "Line 3: …"
    // hosts-file lines ("0.0.0.0 ads.example.com") block that host
    const hostsLine = line.match(/^(?:0\.0\.0\.0|127\.0\.0\.1|::1?|::)\s+([a-z0-9.-]+)(\s+#.*)?$/i);
    if (hostsLine) {
      if (/^(localhost|localhost\.localdomain|local|broadcasthost|0\.0\.0\.0)$/i.test(hostsLine[1])) return;
      line = `||${hostsLine[1]}^`;
    }

    const cm = line.match(/^([^\s#/|$]*?)(#@?#)(.+)$/);
    if (cm) {
      const [, domains, sep, rawSelector] = cm;
      const selector = rawSelector.trim();
      if (!isSafeSelector(selector) || selector.startsWith('+js(')) {
        errors.push(t('bg_filter_bad_selector', lineNo));
        return;
      }
      const hosts = domains.split(',').map(d => d.trim().toLowerCase()).filter(Boolean).map(cosmeticHost);
      if (hosts.includes(null)) {
        errors.push(t('bg_filter_plain_sites', lineNo));
        return;
      }
      if (sep === '#@#') {
        for (const h of hosts) (cosmetic.exceptions[h.replace(/^~/, '')] ||= []).push(selector);
        if (!hosts.length) errors.push(t('bg_filter_exception_site', lineNo));
        return;
      }
      const include = hosts.filter(h => !h.startsWith('~'));
      const exclude = hosts.filter(h => h.startsWith('~')).map(h => h.slice(1));
      if (!include.length) {
        // "##.promo" everywhere, or "~site.com##.promo" everywhere except those sites
        cosmetic.generic.push(selector);
        if (exclude.length) (cosmetic.genericExcluded[selector] ||= []).push(...exclude);
        return;
      }
      for (const h of include) (cosmetic.specific[h] ||= []).push(selector);
      for (const h of exclude) (cosmetic.exceptions[h] ||= []).push(selector);  // "site.com,~sub.site.com##…"
      return;
    }
    if (/#@?#/.test(line)) {
      // "https://example.org/##.ad": an element-hiding filter with an address in front
      errors.push(t('bg_filter_plain_sites', lineNo));
      return;
    }

    let body = line;
    let exception = false;
    if (body.startsWith('@@')) {
      exception = true;
      body = body.slice(2);
    }
    let pattern = body;
    let opts = [];
    const dollar = body.lastIndexOf('$');
    if (dollar !== -1 && /^~?[a-z0-9_-]+(=[^,]*)?(,~?[a-z0-9_-]+(=[^,]*)?)*$/i.test(body.slice(dollar + 1))) {
      pattern = body.slice(0, dollar);
      opts = body.slice(dollar + 1).split(',');
    }
    if (/^(https?:\/\/)?[a-z0-9-]+(\.[a-z0-9-]+)+\/?$/i.test(pattern)) {
      pattern = '||' + pattern.replace(/^https?:\/\//i, '').replace(/\/$/, '') + '^';
    }
    const cond = {};
    const types = new Set();
    const notTypes = new Set();
    for (const rawOpt of opts) {
      const neg = rawOpt.startsWith('~');
      const [nameRaw, value] = (neg ? rawOpt.slice(1) : rawOpt).split('=');
      const name = nameRaw.toLowerCase();
      if (name in TYPE_OPTIONS) (neg ? notTypes : types).add(TYPE_OPTIONS[name]);
      else if (name === 'third-party' || name === '3p') cond.domainType = neg ? 'firstParty' : 'thirdParty';
      else if (name === 'first-party' || name === '1p') cond.domainType = neg ? 'thirdParty' : 'firstParty';
      else if (name === 'all') SAFARI_TYPES.forEach(type => types.add(type));
      else if (name === 'domain' && value) {
        const parsed = parseDomainOption(value);
        if (!parsed) { errors.push(t('bg_filter_bad_domains', lineNo)); return; }
        if (parsed.inc.length) cond.initiatorDomains = parsed.inc;
        if (parsed.exc.length) cond.excludedInitiatorDomains = parsed.exc;
      } else if (name === 'match-case') cond.isUrlFilterCaseSensitive = true;
      else if (name === 'important') { /* custom rules already win */ }
      else { errors.push(t('bg_filter_bad_option', lineNo, name)); return; }
    }
    const pure = pattern.match(/^\|\|([a-z0-9.-]+)\^$/i);
    if (pure) {
      // a whole domain: written as urlFilter, which every Safari applies (Safari before 26 skips
      // requestDomains; tools/convert.py writes the lists' domains the same way)
      cond.urlFilter = '||' + pure[1].toLowerCase() + '^';
    } else {
      let p = pattern.trim();
      if (p.startsWith('||*')) p = p.slice(2);
      if (/\s/.test(p)) {
        errors.push(t('bg_filter_spaces', lineNo));
        return;
      }
      if (!/^[\x21-\x7e]+$/.test(p) || p.length < 3) {
        errors.push(t('bg_filter_short', lineNo));
        return;
      }
      if (p.startsWith('/') && p.endsWith('/') && p.length > 2) {
        errors.push(t('bg_filter_regex', lineNo));
        return;
      }
      cond.urlFilter = p;
    }
    if (types.size) cond.resourceTypes = [...types];
    else if (notTypes.size) cond.excludedResourceTypes = [...notTypes];
    if (exception && types.has('main_frame')) {
      network.push({
        priority: PRIORITY_CUSTOM_ALLOW,
        action: { type: 'allowAllRequests' },
        condition: Object.assign({}, cond, { resourceTypes: ['main_frame', 'sub_frame'] }),
      });
      return;
    }
    network.push({
      priority: exception ? PRIORITY_CUSTOM_ALLOW : PRIORITY_CUSTOM_BLOCK,
      action: { type: exception ? 'allow' : 'block' },
      condition: cond,
    });
  });
  return { network, cosmetic, errors };
}

/* ------------------------------------------------------ dynamic rules */

// Safari only knows initiatorDomains / excludedInitiatorDomains from Safari 26, and even there it
// applies an allow rule's initiatorDomains nowhere ("allow this address on this site" would allow it
// on every site; tested in Safari, macOS 27, 2026-09-23). The older "domains" / "excludedDomains"
// keys work from Safari 15 to 27, so every rule Safari gets uses them (tools/xcode_setup.py does the
// same for the lists). Chromium, used by the tests, gets the standard keys.
const IS_SAFARI = (() => { try { return api.runtime.getURL('').startsWith('safari-web-extension:'); } catch { return false; } })();
function forSafari(rule) {
  if (!IS_SAFARI) return rule;
  const c = Object.assign({}, rule.condition);
  if (c.initiatorDomains) { c.domains = c.initiatorDomains; delete c.initiatorDomains; }
  if (c.excludedInitiatorDomains) { c.excludedDomains = c.excludedInitiatorDomains; delete c.excludedInitiatorDomains; }
  return Object.assign({}, rule, { condition: c });
}

// Safari before 26 (iOS 18, macOS 15 and older) does not apply requestDomains and counts a rule that
// covers frames and other loads twice, so each list also comes as "<id>_compat": every domain as its own
// rule, block rules without types limited to everything but frames (tools/convert.py, compat_rules;
// measured on iOS 18.5: 16 % of the test page blocked with the regular rules, 100 % with these).
// Safari 26 and later, and Chromium (the tests), get the regular rulesets.
const SAFARI_MAJOR = (() => {
  if (!IS_SAFARI) return 0;
  const m = /Version\/(\d+)/.exec((globalThis.navigator && navigator.userAgent) || '');
  return m ? Number(m[1]) : 0;
})();
let compatOverride = null;   // the tests switch the compat rulesets on in Chromium (message debug:compat)
function useCompat(cat) {
  const below = (cat && cat.compatBelowSafari) || 26;
  return compatOverride !== null ? compatOverride : (SAFARI_MAJOR > 0 && SAFARI_MAJOR < below);
}
/** The ruleset that holds a list's rules in this browser. */
function rulesetIdOf(list, cat) {
  return useCompat(cat) && list.compatRules ? list.id + '_compat' : list.id;
}
/** The list a ruleset belongs to. */
function listIdOf(rulesetId) {
  return typeof rulesetId === 'string' && rulesetId.endsWith('_compat') ? rulesetId.slice(0, -7) : rulesetId;
}
/** WebKit rules a list costs in this browser. */
function listCost(list, cat) {
  return useCompat(cat) && list.compatRules ? list.compatWebkitRules : list.webkitRules;
}

// How many dynamic rules the browser accepts (Safari 16.4+: MAX_NUMBER_OF_DYNAMIC_AND_SESSION_RULES,
// Chromium: MAX_NUMBER_OF_DYNAMIC_RULES). Going over makes updateDynamicRules() fail as a whole.
const MAX_DYNAMIC_RULES = (() => {
  const d = api.declarativeNetRequest || {};
  const n = Number(d.MAX_NUMBER_OF_DYNAMIC_RULES || d.MAX_NUMBER_OF_DYNAMIC_AND_SESSION_RULES);
  return n > 0 ? n : 5000;
})();

function buildDynamicRules(settings, custom, max = MAX_DYNAMIC_RULES) {
  const rules = [];
  let dropped = 0;
  const add = rule => { if (rules.length < max) rules.push(rule); else dropped++; };
  // paused sites first: pausing must keep working whatever else the user added
  let pauseId = ID_SITE_PAUSE;
  for (const site of settings.pausedSites) {
    if (!isHostname(site) || pauseId >= ID_CUSTOM_ALLOW) continue;
    add({
      id: pauseId++,
      priority: PRIORITY_SITE_ALLOW,
      action: { type: 'allowAllRequests' },
      condition: { urlFilter: `||${site}^`, resourceTypes: ['main_frame'] },
    });
  }
  let allowId = ID_CUSTOM_ALLOW;
  let blockId = ID_CUSTOM_BLOCK;
  let tooMany = false;
  for (const r of custom.network) {
    const isAllow = r.action.type === 'allow' || r.action.type === 'allowAllRequests';
    if (isAllow ? allowId >= ID_CUSTOM_BLOCK : blockId >= ID_SITE_CONTROL) { tooMany = true; continue; }
    add(Object.assign({ id: isAllow ? allowId++ : blockId++ }, r));
  }
  if (tooMany) custom.errors.push(t('bg_filter_too_many', formatNumber(10000), formatNumber(10000)));
  let controlId = ID_SITE_CONTROL;
  for (const [site, c] of Object.entries(settings.siteControls || {})) {
    if (!isHostname(site) || !c) continue;
    if (c.fonts) {
      add({
        id: controlId++, priority: PRIORITY_SITE_CONTROL, action: { type: 'block' },
        condition: { initiatorDomains: [site], resourceTypes: ['font'] },
      });
    }
    if (c.scripts3p) {
      add({
        id: controlId++, priority: PRIORITY_SITE_CONTROL, action: { type: 'block' },
        condition: { initiatorDomains: [site], domainType: 'thirdParty', resourceTypes: ['script'] },
      });
    }
  }
  if (dropped) {
    custom.errors.push(t('bg_rule_limit', formatNumber(max), formatNumber(dropped)));
  }
  return rules;
}

/* ------------------------------------------------- applying the settings */

let applyChain = Promise.resolve();
let applying = false;

/** Applies the saved settings to Safari. Resolves to { ok: true } or { ok: false, error }. */
function applySettings(options = {}) {
  const job = applyChain.then(async () => {
    applying = true;
    try {
      await doApply(options);
      await api.storage.local.remove('lastError');
      return { ok: true };
    } finally {
      applying = false;
    }
  }).catch(async err => {
    const message = String(err && err.message || err);
    console.warn('Bouclier: applying settings failed', message);
    try { await api.storage.local.set({ lastError: { at: Date.now(), message } }); } catch { /* storage full */ }
    return { ok: false, error: message };
  });
  applyChain = job;
  return job;
}

// Settings are read, changed and saved one change at a time, so two quick taps never undo each other.
let settingsChain = Promise.resolve();
function mutateSettings(fn) {
  const job = settingsChain.then(async () => {
    const s = await getSettings();
    const result = await fn(s);
    if (result !== false) await saveSettings(s);   // fn returns false: nothing to save
    return s;
  });
  settingsChain = job.catch(() => {});
  return job;
}

/** Ends timed pauses that are over. Returns true when something changed. */
function purgeExpired(s, now = Date.now()) {
  let changed = false;
  if (s.paused && s.pausedUntil && s.pausedUntil <= now) {
    s.paused = false;
    s.pausedUntil = 0;
    changed = true;
  }
  if (!s.paused && s.pausedUntil) {
    s.pausedUntil = 0;
    changed = true;
  }
  for (const [site, until] of Object.entries(s.sitePauseUntil)) {
    if (!s.pausedSites.includes(site)) {
      delete s.sitePauseUntil[site];
      changed = true;
    } else if (until <= now) {
      delete s.sitePauseUntil[site];
      s.pausedSites = s.pausedSites.filter(x => x !== site);
      changed = true;
    }
  }
  return changed;
}

async function scheduleAlarms(s) {
  if (!api.alarms) return;
  const existing = await api.alarms.getAll();
  const want = new Map();
  if (s.paused && s.pausedUntil) want.set('resume-all', s.pausedUntil);
  for (const [site, until] of Object.entries(s.sitePauseUntil)) want.set(`resume-site|${site}`, until);
  for (const a of existing) {
    if ((a.name === 'resume-all' || a.name.startsWith('resume-site|')) && want.get(a.name) !== a.scheduledTime) {
      await api.alarms.clear(a.name);
    }
  }
  for (const [name, when] of want) {
    const current = existing.find(a => a.name === name);
    if (!current || current.scheduledTime !== when) await api.alarms.create(name, { when: Math.max(when, Date.now() + 1000) });
  }
  if (!existing.some(a => a.name === 'stats-flush')) await api.alarms.create('stats-flush', { periodInMinutes: 5 });
}

// Bumped whenever the rules change, so a cache built from older settings is never kept.
let cacheGeneration = 0;
function dropCaches() {
  cacheGeneration++;
  cosmeticCache = null;
  popupMatcher = null;
}

async function doApply({ force = false } = {}) {
  const s = await mutateSettings(x => purgeExpired(x));
  const cat = await getCatalogue();
  const custom = parseCustomFilters(s.customFilters);
  const byId = Object.fromEntries(cat.lists.map(l => [l.id, l]));
  const problems = [];

  // 1. which rulesets fit in Safari's budget
  const dynamic = s.paused ? [] : buildDynamicRules(s, custom);
  let cost = dynamic.reduce((n, r) => n + webkitCount(r), 0);
  const enabled = [];
  const overBudget = [];
  for (const id of s.paused ? [] : s.enabledLists) {
    const list = byId[id];
    if (!list) continue;
    if (cost + listCost(list, cat) > WEBKIT_BUDGET) {
      overBudget.push(id);
      continue;
    }
    cost += listCost(list, cat);
    enabled.push(id);
  }

  // Each step runs even when an earlier one failed, so one problem (for example too many rules of
  // the user's own) never leaves content scripts, alarms or the saved state behind.
  // problem(detail) is what the menu and the settings page show when the step fails ("your rules: …").
  const step = async (problem, fn) => {
    try { await fn(); } catch (err) { problems.push(problem(String(err && err.message || err))); }
  };

  // 2. static rulesets
  await step(detail => t('bg_problem_lists', detail), async () => {
    const want = enabled.map(id => rulesetIdOf(byId[id], cat));
    const current = await api.declarativeNetRequest.getEnabledRulesets();
    const enable = want.filter(id => !current.includes(id));
    const disable = current.filter(id => !want.includes(id));
    if (enable.length || disable.length) {
      await api.declarativeNetRequest.updateEnabledRulesets({ enableRulesetIds: enable, disableRulesetIds: disable });
    }
  });

  // 3. dynamic rules (paused sites + custom filters + site controls)
  await step(detail => t('bg_problem_rules', detail), async () => {
    const signature = JSON.stringify(dynamic);
    const { dnrSignature } = await api.storage.local.get('dnrSignature');
    const existing = await api.declarativeNetRequest.getDynamicRules();
    if (force || dnrSignature !== signature || existing.length !== dynamic.length) {
      await api.storage.local.remove('dnrSignature');   // a failed update below must be retried next time
      await api.declarativeNetRequest.updateDynamicRules({
        removeRuleIds: existing.map(r => r.id),
        addRules: dynamic.map(forSafari),
      });
      await api.storage.local.set({ dnrSignature: signature });
    }
  });

  // 4. content scripts
  await step(detail => t('bg_problem_hiding', detail), () => syncContentScripts(s, cat, enabled, force));

  // 5. toolbar count, alarms, menu
  try {
    await api.declarativeNetRequest.setExtensionActionOptions({ displayActionCountAsBadgeText: !!s.showBadge && !s.paused });
  } catch { /* Safari before 16.4 */ }
  await scheduleAlarms(s).catch(() => {});

  await api.storage.local.set({
    state: {
      appliedAt: Date.now(),
      enabledLists: enabled,
      overBudget,
      webkitRules: cost,
      customErrors: custom.errors,
      listsBuilt: cat.generated,
      problems,
    },
  });
  dropCaches();   // after the new state is saved, so a page loading meanwhile cannot rebuild an old cache
  await refreshAllTabIcons(s);
  await refreshSiteMenu(null, s);
  if (problems.length) throw new Error(problems.join('; '));
}

/** Removes the keys older Safari versions do not know, one at a time, until Safari accepts the call. */
function withoutNewerKeys(items, keys) {
  return items.map(item => {
    const copy = Object.assign({}, item);
    for (const k of keys) delete copy[k];
    return copy;
  });
}

async function registerScripts(scripts) {
  // cssOrigin needs Safari 18 (Chromium, used by the tests, has none): without it the element-hiding
  // style sheets are ordinary page style sheets, which still hide with !important.
  const attempts = [[], ['cssOrigin']];
  let lastErr = null;
  for (const drop of attempts) {
    try {
      await api.scripting.registerContentScripts(drop.length ? withoutNewerKeys(scripts, drop) : scripts);
      return;
    } catch (err) {
      lastErr = err;
      if (/duplicate/i.test(String(err && err.message))) break;   // retrying without keys cannot help
    }
  }
  throw lastErr;
}

/** insertCSS as a user style sheet (Safari 18+), or as a page style sheet on older Safari. */
async function insertUserCSS(details) {
  try {
    await api.scripting.insertCSS(Object.assign({ origin: 'USER' }, details));
  } catch (err) {
    if (/no tab|not found|closed|frame/i.test(String(err && err.message))) throw err;
    await api.scripting.insertCSS(details);
  }
}

async function syncContentScripts(s, cat, enabledLists, force) {
  const desired = [];
  if (!s.paused) {
    const exclude = sitePatterns(s.pausedSites);
    const withExclude = (obj, extra = []) => {
      const all = exclude.concat(extra);
      if (all.length) obj.excludeMatches = all;
      return obj;
    };
    for (const id of enabledLists) {
      const list = cat.lists.find(l => l.id === id);
      if (!list || !list.genericSelectors) continue;
      desired.push(withExclude({
        id: `${CS_PREFIX}css-${id}`,
        css: [`cosmetic/${id}.generic.css`],
        matches: ['<all_urls>'],
        allFrames: true,
        runAt: 'document_start',
        cssOrigin: 'user',
      }, sitePatterns(list.noGenericHideSites)));
    }
    desired.push(withExclude({
      id: `${CS_PREFIX}cosmetic`,
      js: ['content/cosmetic.js'],
      matches: ['<all_urls>'],
      allFrames: true,
      runAt: 'document_start',
    }));
    if (s.youtube) {
      desired.push(withExclude({
        id: `${CS_PREFIX}youtube`,
        js: ['content/youtube-main.js'],
        matches: YT_MATCHES,
        allFrames: true,
        runAt: 'document_start',
        world: 'MAIN',
      }));
    }
  } else {
    // keep the page helper so the picker, toasts and the right-click menu still work
    desired.push({
      id: `${CS_PREFIX}cosmetic`,
      js: ['content/cosmetic.js'],
      matches: ['<all_urls>'],
      allFrames: true,
      runAt: 'document_start',
    });
  }
  const signature = JSON.stringify(desired);
  const { csSignature } = await api.storage.local.get('csSignature');
  const registered = await api.scripting.getRegisteredContentScripts();
  const ours = registered.filter(r => r.id.startsWith(CS_PREFIX));
  if (!force && csSignature === signature && ours.length === desired.length) return;
  if (ours.length) await api.scripting.unregisterContentScripts({ ids: ours.map(r => r.id) });
  if (desired.length) await registerScripts(desired);
  await api.storage.local.set({ csSignature: signature });
}

/* ------------------------------------------------------ element hiding */

let cosmeticCache = null;

async function loadJSON(path) {
  const res = await fetch(api.runtime.getURL(path));
  return res.json();
}

async function getCosmeticData(s) {
  if (cosmeticCache) return cosmeticCache;
  const generation = cacheGeneration;
  const { state } = await api.storage.local.get('state');
  const enabled = (state && state.enabledLists) || s.enabledLists;
  const sources = await Promise.all(enabled.map(id => loadJSON(`cosmetic/${id}.json`).catch(() => null)));
  const lists = sources.filter(Boolean);
  if (s.youtube) lists.push(await loadJSON('cosmetic/builtin-youtube.json'));
  if (s.youtubeHideShorts) lists.push(await loadJSON('cosmetic/builtin-youtube-shorts.json'));
  const custom = parseCustomFilters(s.customFilters).cosmetic;
  lists.push({
    specific: Object.assign({}, custom.specific),
    exceptions: custom.exceptions,
    genericDynamic: custom.generic,
    genericExcluded: custom.genericExcluded,
  });
  const picked = {};
  for (const [host, sels] of Object.entries(s.pickedHides || {})) picked[host] = sels;
  const comments = {};
  for (const [site, c] of Object.entries(s.siteControls || {})) if (c && c.comments) comments[site] = COMMENT_SELECTORS;
  lists.push({ specific: picked });
  lists.push({ specific: comments });
  const data = { lists };
  if (generation === cacheGeneration) cosmeticCache = data;   // settings changed meanwhile: don't keep it
  return data;
}

function selectorsFor(host, data) {
  const candidates = hostCandidates(host);
  const exceptions = new Set();
  let elemHide = false;
  let genericHide = false;
  let specificHide = false;
  for (const l of data.lists) {
    for (const c of candidates) {
      const ex = l.exceptions && l.exceptions[c];
      if (ex) ex.forEach(sel => exceptions.add(sel));
      const neg = l.specificNeg && l.specificNeg[c];
      if (neg) neg.forEach(sel => exceptions.add(sel));
    }
    if (l.elemHide && candidates.some(c => l.elemHide.includes(c))) elemHide = true;
    if (l.genericHide && candidates.some(c => l.genericHide.includes(c))) genericHide = true;
    if (l.specificHide && candidates.some(c => l.specificHide.includes(c))) specificHide = true;
  }
  if (elemHide) return [];
  const out = new Set();
  for (const l of data.lists) {
    if (!specificHide && l.specific) {
      for (const c of candidates) {
        const sels = l.specific[c];
        if (sels) sels.forEach(sel => out.add(sel));
      }
    }
    if (!genericHide && l.genericDynamic) {
      for (const sel of l.genericDynamic) {
        const excluded = l.genericExcluded && l.genericExcluded[sel];
        if (excluded && candidates.some(c => excluded.includes(c))) continue;
        out.add(sel);
      }
    }
  }
  for (const sel of exceptions) out.delete(sel);
  return [...out];
}

async function injectCosmetics(msg, sender) {
  const tabId = sender.tab && sender.tab.id;
  if (tabId == null || tabId < 0) return { ok: false };
  const s = await getSettings();
  const host = hostnameOf(msg.url);
  const topHost = hostnameOf(sender.tab.url || '') || host;
  if (!host || s.paused || isSitePaused(topHost, s.pausedSites) || isSitePaused(host, s.pausedSites)) {
    return { ok: true, off: true };
  }
  const data = await getCosmeticData(s);
  const selectors = selectorsFor(host, data);
  if (selectors.length) {
    // one rule per selector: a selector Safari does not understand only drops itself
    const css = selectors.map(sel => `${sel}{display:none!important}`).join('\n');
    await insertUserCSS({ target: { tabId, frameIds: [sender.frameId || 0] }, css });
  }
  return { ok: true, count: selectors.length };
}

/* ---------------------------------------------------------- statistics */

/*
 * Safari's getMatchedRules() returns { request: { url }, timeStamp, tabId } for every
 * load it blocked (no rule details). Chromium returns { rule: { ruleId, rulesetId } }
 * instead, allow rules included. Both shapes are handled.
 */

function isAllowRule(rule, cat) {
  if (rule.rulesetId === '_dynamic' || rule.rulesetId === '_session') return rule.ruleId < ID_CUSTOM_BLOCK;
  const id = listIdOf(rule.rulesetId);
  const list = cat.lists.find(l => l.id === id);
  if (!list) return false;
  return rule.ruleId <= ((id === rule.rulesetId ? list.allowIdMax : list.compatAllowIdMax) || 0);
}

function categoryOf(rule) {
  if (rule.rulesetId === '_dynamic' || rule.rulesetId === '_session') return rule.ruleId >= ID_SITE_CONTROL ? 'site' : 'custom';
  return CATEGORY_OF_LIST[listIdOf(rule.rulesetId)] || 'other';
}

/** One matched load -> { ts, host, category }, or null for allow-rule matches. */
function normalizeMatch(m, cat, params) {
  const ts = m.timeStamp < 1e12 ? m.timeStamp * 1000 : m.timeStamp;
  const url = (m.request && m.request.url) || '';
  const host = url ? hostnameOf(url) : null;
  let category;
  if (m.rule) {
    if (isAllowRule(m.rule, cat)) return null;
    category = categoryOf(m.rule);
  } else {
    // Safari reports blocked loads only, without saying which rule matched
    category = 'blocked';
  }
  return { ts, host, category };
}

async function getTabState() {
  return (await session.get('tabState')) || {};
}

let statsChain = Promise.resolve();
function statsTask(fn) {
  statsChain = statsChain.then(fn).catch(err => console.warn('Bouclier stats', err));
  return statsChain;
}

async function loadStats() {
  const { stats } = await api.storage.local.get('stats');
  const s = stats && stats.days ? stats : { since: Date.now(), days: {}, sites: {} };
  if (!s.domains) s.domains = {};
  return s;
}

const NOT_BLOCKS = new Set(['links', 'popups']);

function trimMap(obj, max, keep) {
  const entries = Object.entries(obj);
  return entries.length > max ? Object.fromEntries(entries.sort((a, b) => b[1] - a[1]).slice(0, keep)) : obj;
}

async function addToStats(counts, sites, domains) {
  if (!Object.values(counts).some(Boolean)) return;
  const stats = await loadStats();
  const key = dayKey();
  const today = stats.days[key] || (stats.days[key] = {});
  for (const [k, v] of Object.entries(counts)) today[k] = (today[k] || 0) + v;
  for (const [site, n] of Object.entries(sites || {})) if (site && n) stats.sites[site] = (stats.sites[site] || 0) + n;
  for (const [d, n] of Object.entries(domains || {})) stats.domains[d] = (stats.domains[d] || 0) + n;
  const days = Object.keys(stats.days).sort();
  while (days.length > 90) delete stats.days[days.shift()];
  stats.sites = trimMap(stats.sites, 300, 200);
  stats.domains = trimMap(stats.domains, 400, 250);
  await api.storage.local.set({ stats });
}

const msTime = ts => (ts < 1e12 ? ts * 1000 : ts);

// Chromium allows ~20 getMatchedRules() calls per 10 minutes; Safari has no quota.
// One call covers every tab, flushes are coalesced, and the last answer is reused briefly.
let lastMatched = null; // { at, info }

async function allMatched(maxAgeMs = 0) {
  if (lastMatched && Date.now() - lastMatched.at <= maxAgeMs) return lastMatched.info;
  try {
    const res = await api.declarativeNetRequest.getMatchedRules({});
    lastMatched = { at: Date.now(), info: (res && res.rulesMatchedInfo) || [] };
    return lastMatched.info;
  } catch {
    return lastMatched ? lastMatched.info : null;
  }
}

function siteAt(tabInfo, ts) {
  const history = tabInfo.history || [];
  for (let i = history.length - 1; i >= 0; i--) if (ts >= history[i].from) return history[i].site;
  return history.length ? history[0].site : tabInfo.site;
}

/** Moves new matches of every tab into the saved statistics, credited to the site shown at the time. */
function flushAll() {
  return statsTask(async () => {
    const info = await allMatched(0);
    if (!info || !info.length) return;
    const cat = await getCatalogue();
    const params = null;
    const tabs = await getTabState();
    const counts = {};
    const domains = {};
    const sites = {};
    const newLast = {};
    for (const raw of info) {
      if (raw.tabId == null || raw.tabId < 0) continue;
      const tabInfo = tabs[raw.tabId] || (tabs[raw.tabId] = {});
      const ts = msTime(raw.timeStamp);
      if (!(ts > (tabInfo.last || 0))) continue;
      newLast[raw.tabId] = Math.max(newLast[raw.tabId] || 0, ts);
      const m = normalizeMatch(raw, cat, params);
      if (!m) continue;
      counts[m.category] = (counts[m.category] || 0) + 1;
      if (!NOT_BLOCKS.has(m.category) && !tabInfo.incognito) {
        const site = siteAt(tabInfo, ts);
        if (site) sites[site] = (sites[site] || 0) + 1;
        if (m.host) {
          const d = baseDomain(m.host);
          domains[d] = (domains[d] || 0) + 1;
        }
      }
    }
    let changed = false;
    for (const [id, ts] of Object.entries(newLast)) {
      tabs[id].last = ts;
      changed = true;
    }
    for (const [id, tabInfo] of Object.entries(tabs)) {
      if (tabInfo.closedAt && Date.now() - tabInfo.closedAt > 10 * 60000) {
        delete tabs[id];
        changed = true;
      }
    }
    if (changed) await session.set('tabState', tabs);
    await addToStats(counts, sites, domains);
  });
}

let flushTimer = null;
function scheduleFlush(delay = 3000) {
  if (flushTimer) return;
  flushTimer = setTimeout(() => {
    flushTimer = null;
    flushAll();
  }, delay);
}

/** A new page starts loading in a tab: remember when, and which site it is. */
function noteNavigation(tabId, url, when) {
  return statsTask(async () => {
    let incognito = false;
    try { incognito = !!(await api.tabs.get(tabId)).incognito; } catch { /* tab gone */ }
    const tabs = await getTabState();
    const tabInfo = tabs[tabId] || {};
    const host = incognito ? null : hostnameOf(url || '');
    tabInfo.incognito = incognito;
    tabInfo.nav = when || Date.now();
    tabInfo.site = host ? siteKey(host) : undefined;
    tabInfo.history = (tabInfo.history || []).concat({ from: tabInfo.nav, site: tabInfo.site }).slice(-6);
    tabs[tabId] = tabInfo;
    await session.set('tabState', tabs);
  });
}

function onNavigationStart(tabId, url, when) {
  scheduleFlush();
  return noteNavigation(tabId, url, when);
}

async function flushAllTabs() {
  await flushAll();
}

/** What Bouclier did on the page currently shown in a tab. */
async function pageBreakdown(tabId) {
  const all = await allMatched(2000);
  if (!all) return null;
  const info = all.filter(m => m.tabId === tabId);
  const cat = await getCatalogue();
  const params = null;
  const tabs = await getTabState();
  const since = (tabs[tabId] && tabs[tabId].nav) || 0;
  const counts = {};
  const hosts = {};
  let blocked = 0;
  for (const raw of info) {
    const m = normalizeMatch(raw, cat, params);
    if (!m || m.ts < since) continue;
    counts[m.category] = (counts[m.category] || 0) + 1;
    if (!NOT_BLOCKS.has(m.category)) {
      blocked++;
      if (m.host) hosts[m.host] = (hosts[m.host] || 0) + 1;
    }
  }
  const topHosts = Object.entries(hosts).sort((a, b) => b[1] - a[1]).slice(0, 6).map(([host, n]) => ({ host, n }));
  return { blocked, counts, topHosts };
}

function summarizeStats(stats) {
  const days = stats.days || {};
  const sum = obj => Object.entries(obj || {}).reduce((n, [k, v]) => n + (NOT_BLOCKS.has(k) ? 0 : v), 0);
  const today = days[dayKey()] || {};
  let week = 0;
  let all = 0;
  const totals = {};
  const weekAgo = Date.now() - 6 * 86400000;
  for (const [day, counts] of Object.entries(days)) {
    all += sum(counts);
    if (new Date(day + 'T23:59:59').getTime() >= weekAgo) week += sum(counts);
    for (const [k, v] of Object.entries(counts)) totals[k] = (totals[k] || 0) + v;
  }
  const series = [];
  for (let i = 13; i >= 0; i--) {
    const key = dayKey(Date.now() - i * 86400000);
    series.push({ day: key, counts: days[key] || {} });
  }
  const top = obj => Object.entries(obj || {}).sort((a, b) => b[1] - a[1]).slice(0, 10);
  return {
    since: stats.since,
    today: sum(today),
    todayCounts: today,
    week,
    all,
    totals,
    series,
    topSites: top(stats.sites),
    topDomains: top(stats.domains),
    bytesSaved: all * BYTES_PER_BLOCK,
    bytesPerBlock: BYTES_PER_BLOCK,
    labels: categoryLabels(),
  };
}

/* -------------------------------------------------------- pop-up blocker */

let popupMatcher = null;

async function getPopupMatcher() {
  if (popupMatcher) return popupMatcher;
  const generation = cacheGeneration;
  const { state } = await api.storage.local.get('state');
  const enabled = (state && state.enabledLists) || [];
  let data = {};
  try { data = await loadJSON('data/popups.json'); } catch { /* none */ }
  const build = () => ({ hosts: new Map(), patterns: [] });
  const block = build();
  const allow = build();
  const add = (target, e) => {
    if (e.h) {
      const arr = target.hosts.get(e.h) || [];
      arr.push(e);
      target.hosts.set(e.h, arr);
    } else if (e.r) {
      try { target.patterns.push(Object.assign({ re: new RegExp(e.r) }, e)); } catch { /* bad pattern */ }
    }
  };
  for (const id of enabled) {
    const d = data[id];
    if (!d) continue;
    (d.block || []).forEach(e => add(block, e));
    (d.allow || []).forEach(e => add(allow, e));
  }
  const matcher = { block, allow };
  if (generation === cacheGeneration) popupMatcher = matcher;
  return matcher;
}

function popupEntryApplies(e, popupHost, openerHost) {
  if (e.d && !(openerHost && e.d.some(d => hostMatches(openerHost, d)))) return false;
  if (e.nd && openerHost && e.nd.some(d => hostMatches(openerHost, d))) return false;
  if (e.tp !== undefined && openerHost) {
    const third = baseDomain(popupHost) !== baseDomain(openerHost);
    if (e.tp !== third) return false;
  }
  return true;
}

function findPopupEntry(set, url, popupHost, openerHost) {
  const parts = popupHost.split('.');
  for (let i = 0; i < parts.length; i++) {
    const arr = set.hosts.get(parts.slice(i).join('.'));
    if (arr) {
      const hit = arr.find(e => popupEntryApplies(e, popupHost, openerHost));
      if (hit) return hit;
    }
  }
  const lower = url.toLowerCase();
  return set.patterns.find(e => e.re.test(lower) && popupEntryApplies(e, popupHost, openerHost)) || null;
}

async function isPopupAd(url, openerHost) {
  const host = hostnameOf(url);
  if (!host) return false;
  const m = await getPopupMatcher();
  if (findPopupEntry(m.allow, url, host, openerHost)) return false;
  return !!findPopupEntry(m.block, url, host, openerHost);
}

const popupWatch = new Map(); // tabId -> { opener, until }

async function checkPopup(tabId, url) {
  const w = popupWatch.get(tabId);
  if (!w) return;
  if (Date.now() > w.until) {
    popupWatch.delete(tabId);
    return;
  }
  if (!/^https?:/i.test(url || '')) return;
  const s = await getSettings();
  if (!s.popupBlocker || s.paused) return;
  let openerUrl = '';
  try { openerUrl = (await api.tabs.get(w.opener)).url || ''; } catch { /* opener closed */ }
  const openerHost = hostnameOf(openerUrl);
  if (isSitePaused(openerHost, s.pausedSites)) return;
  if (!(await isPopupAd(url, openerHost))) return;
  popupWatch.delete(tabId);
  try {
    await api.tabs.remove(tabId);
  } catch {
    return;
  }
  await statsTask(() => addToStats({ popups: 1 }, null, null));
  try {
    await api.tabs.sendMessage(w.opener, { type: 'toast', text: t('bg_toast_popup_closed', hostnameOf(url)) }, { frameId: 0 });
  } catch { /* page without helper */ }
}

api.tabs.onCreated.addListener(tab => {
  if (tab.openerTabId == null || tab.openerTabId < 0 || tab.id == null) return;
  popupWatch.set(tab.id, { opener: tab.openerTabId, until: Date.now() + 15000 });
  const url = tab.pendingUrl || tab.url;
  if (url) checkPopup(tab.id, url);
});
const hasWebNavigation = !!(api.webNavigation && api.webNavigation.onBeforeNavigate);
if (hasWebNavigation) {
  api.webNavigation.onBeforeNavigate.addListener(d => {
    if (d.frameId !== 0 || d.tabId == null || d.tabId < 0) return;
    if (popupWatch.has(d.tabId)) checkPopup(d.tabId, d.url);
    onNavigationStart(d.tabId, d.url, d.timeStamp ? msTime(d.timeStamp) : Date.now());
  });
}

/* --------------------------------------------------------------- icons */

const ICON_ON = { 16: 'icons/toolbar-16.png', 19: 'icons/toolbar-19.png', 32: 'icons/toolbar-32.png', 38: 'icons/toolbar-38.png', 48: 'icons/toolbar-48.png' };
const ICON_OFF = { 16: 'icons/toolbar-off-16.png', 19: 'icons/toolbar-off-19.png', 32: 'icons/toolbar-off-32.png', 38: 'icons/toolbar-off-38.png', 48: 'icons/toolbar-off-48.png' };

async function refreshTabIcon(tab, s) {
  if (!tab || tab.id == null || tab.id < 0) return;
  const host = hostnameOf(tab.url || '');
  const off = s.paused || isSitePaused(host, s.pausedSites);
  try {
    await api.action.setIcon({ tabId: tab.id, path: off ? ICON_OFF : ICON_ON });
  } catch { /* tab went away */ }
}

async function refreshAllTabIcons(s) {
  try {
    const tabs = await api.tabs.query({});
    await Promise.all(tabs.map(tab => refreshTabIcon(tab, s)));
  } catch { /* no tabs permission yet */ }
}

/* ------------------------------------------------ right-click menu */

let menusChain = Promise.resolve();
function setupMenus() {
  if (!menus) return Promise.resolve();
  menusChain = menusChain.then(async () => {
    try {
      await menus.removeAll();
      const contexts = ['page', 'frame', 'image', 'link', 'video', 'audio', 'selection'];
      const create = props => new Promise(resolve => {
        try {
          menus.create(props, () => { void (api.runtime.lastError); resolve(); });
        } catch { resolve(); }
      });
      await create({ id: 'bouclier-hide', title: t('bg_menu_hide'), contexts });
      await create({ id: 'bouclier-site', title: t('bg_menu_pause_this_site'), contexts });
    } catch (err) {
      console.warn('Bouclier menus', err);
    }
  });
  return menusChain;
}

async function refreshSiteMenu(tab, s) {
  if (!menus) return;
  try {
    if (!tab) [tab] = await api.tabs.query({ active: true, lastFocusedWindow: true });
    const host = tab ? hostnameOf(tab.url || '') : null;
    s = s || await getSettings();
    const site = host ? siteKey(host) : null;
    const paused = isSitePaused(host, s.pausedSites);
    await menus.update('bouclier-site', {
      title: !site ? t('bg_menu_pause_this_site') : paused ? t('bg_menu_resume_site', site) : t('bg_menu_pause_site', site),
      enabled: !!site && !s.paused,
    });
  } catch { /* menu not created yet */ }
}

if (menus && menus.onClicked) {
  menus.onClicked.addListener(async (info, tab) => {
    if (!tab || tab.id == null) return;
    if (info.menuItemId === 'bouclier-hide') {
      // inside a frame: offer the whole frame, picked in the top page and saved for that site
      await startPicker(tab.id, { preselect: true, frameId: 0, frameUrl: info.frameId ? (info.frameUrl || '') : '' });
    } else if (info.menuItemId === 'bouclier-site') {
      const host = hostnameOf(tab.url || info.pageUrl || '');
      if (!host) return;
      await toggleSite(host);
      try { await api.tabs.reload(tab.id); } catch { /* ignore */ }
    }
  });
}

/* ---------------------------------------------------- keyboard shortcuts */

async function activeTab() {
  const [tab] = await api.tabs.query({ active: true, lastFocusedWindow: true });
  return tab || null;
}

async function runCommand(command) {
  const tab = await activeTab();
  if (command === 'toggle-site') {
    const host = tab && hostnameOf(tab.url || '');
    if (!host) return { ok: false };
    await toggleSite(host);
    try { await api.tabs.reload(tab.id); } catch { /* ignore */ }
    return { ok: true };
  }
  if (command === 'pause-all') {
    await update(s => {
      s.paused = !s.paused;
      s.pausedUntil = 0;
    });
    if (tab) try { await api.tabs.reload(tab.id); } catch { /* ignore */ }
    return { ok: true };
  }
  if (command === 'hide-element') {
    if (!tab) return { ok: false };
    return startPicker(tab.id, {});
  }
  return { ok: false };
}

if (api.commands && api.commands.onCommand) {
  api.commands.onCommand.addListener(command => { runCommand(command).catch(() => {}); });
}

/* ------------------------------------------------------------ messages */

/** Changes the settings (one change at a time) and applies them. Resolves to { ok } or { ok: false, error }. */
async function update(mutator, opts) {
  await mutateSettings(mutator);
  return applySettings(opts);
}

/** Why a pause was refused ("Bouclier cannot pause this address…"), for the menu. */
const cannotPause = () => t('bg_cannot_pause');

/** Pauses (paused = true), resumes (false) or flips (undefined) protection on a site. */
function setSitePaused(host, paused) {
  host = String(host || '').toLowerCase();
  const key = siteKey(host);
  if (!isHostname(host) || !isHostname(key)) return Promise.resolve({ ok: false, error: cannotPause() });
  return update(s => {
    const site = pausedSiteFor(host, s.pausedSites);
    const want = typeof paused === 'boolean' ? paused : !site;
    if (!want && site) {
      s.pausedSites = s.pausedSites.filter(x => !(host === x || host.endsWith('.' + x)));
      delete s.sitePauseUntil[site];
    } else if (want && !site) {
      s.pausedSites = s.pausedSites.concat(key);
    } else {
      return false;   // already as asked
    }
  });
}

function toggleSite(host) {
  return setSitePaused(host, undefined);
}

function tomorrowMorning() {
  const d = new Date();
  d.setDate(d.getDate() + 1);
  d.setHours(6, 0, 0, 0);
  return d.getTime();
}

const MAX_PAUSE_MINUTES = 7 * 24 * 60;

/** When a timed pause asked by the popup ends: { minutes } or { until: 'tomorrow' }; 0 when the request makes no sense. */
function pauseEnd(msg) {
  if (msg && msg.until === 'tomorrow') return tomorrowMorning();
  const minutes = Number(msg && msg.minutes);
  if (!(minutes > 0 && minutes <= MAX_PAUSE_MINUTES)) return 0;
  return Date.now() + minutes * 60000;
}

async function addPicked(msg, sender) {
  const host = hostnameOf(msg.url || (sender.tab && sender.tab.url) || '');
  const selector = String(msg.selector || '').trim();
  if (!host || !isSafeSelector(selector)) return { ok: false };
  const key = siteKey(host);
  await mutateSettings(s => {
    const list = s.pickedHides[key] || [];
    if (list.includes(selector)) return false;
    list.push(selector);
    s.pickedHides[key] = list.slice(-500);
  });
  dropCaches();
  if (sender.tab && sender.tab.id != null) {
    await insertUserCSS({
      target: { tabId: sender.tab.id, frameIds: [sender.frameId || 0] },
      css: `${selector}{display:none!important}`,
    }).catch(() => {});
  }
  return { ok: true };
}

async function startPicker(tabId, opts = {}) {
  const message = { type: 'picker:start', preselect: !!opts.preselect, frameUrl: opts.frameUrl || '' };
  const frameId = opts.frameId || 0;
  try {
    await api.tabs.sendMessage(tabId, message, { frameId });
  } catch {
    try {
      await api.scripting.executeScript({ target: { tabId, frameIds: [frameId] }, files: ['content/cosmetic.js'] });
      await api.tabs.sendMessage(tabId, message, { frameId });
    } catch {
      return { ok: false };
    }
  }
  return { ok: true };
}

async function blockedCount(tabId) {
  const b = await pageBreakdown(tabId);
  if (b) return b.blocked;
  try {
    const text = await api.action.getBadgeText({ tabId });
    return parseInt(text, 10) || 0;
  } catch {
    return null;
  }
}

// Safari keeps "Always Allow on Every Website" (or "Other websites: Allow") as *://*/*, every site
// over http and https. WebKit does not count that as <all_urls>, which also covers file: and other
// schemes, so checking <all_urls> showed the "Allow" banner for good on a Mac already allowed
// everywhere, and the Allow button could not make it go away (tests/webkit-bench/PermissionProbe.swift).
const EVERY_WEBSITE = '*://*/*';

async function hasAllSitesAccess() {
  try { return await api.permissions.contains({ origins: [EVERY_WEBSITE] }); } catch { return true; }
}

/** Access to this page's site only ("Always Allow on This Website"): enough for hiding an element there. */
async function hasSiteAccess(url) {
  try {
    const u = new URL(url);
    if (u.protocol !== 'http:' && u.protocol !== 'https:') return false;
    return await api.permissions.contains({ origins: [`${u.protocol}//${u.hostname}/*`] });
  } catch {
    return false;
  }
}

async function popupState(tabId) {
  const s = await getSettings();
  const cat = await getCatalogue();
  const { state, lastError } = await api.storage.local.get(['state', 'lastError']);
  let tab = null;
  try { tab = await api.tabs.get(tabId); } catch { /* none */ }
  const host = tab ? hostnameOf(tab.url || '') : null;
  const site = host ? siteKey(host) : null;
  await flushAll();
  const breakdown = tabId != null ? await pageBreakdown(tabId) : null;
  const pausedSite = pausedSiteFor(host, s.pausedSites);
  const summary = summarizeStats(await loadStats());
  return {
    version: api.runtime.getManifest().version,
    host,
    site,
    paused: s.paused,
    pausedUntil: s.pausedUntil || 0,
    sitePaused: !!pausedSite,
    sitePausedUntil: pausedSite ? (s.sitePauseUntil[pausedSite] || 0) : 0,
    youtube: s.youtube,
    youtubeHideShorts: s.youtubeHideShorts,
    popupBlocker: s.popupBlocker,
    blocked: breakdown ? breakdown.blocked : await blockedCount(tabId),
    breakdown: breakdown ? breakdown.counts : null,
    topHosts: breakdown ? breakdown.topHosts : [],
    labels: categoryLabels(),
    today: summary.today,
    todayPopups: (summary.todayCounts && summary.todayCounts.popups) || 0,
    hiddenCount: site && s.pickedHides[site] ? s.pickedHides[site].length : 0,
    siteControls: (site && s.siteControls[site]) || {},
    lists: cat.lists.map(l => ({
      id: l.id, name: l.name, description: l.description,
      enabled: s.enabledLists.includes(l.id),
      active: !!(state && state.enabledLists && state.enabledLists.includes(l.id)),
    })),
    overBudget: (state && state.overBudget) || [],
    applying,
    hostAccess: await hasAllSitesAccess(),
    siteAccess: host ? await hasSiteAccess(tab.url) : false,
    lastError: lastError || null,
  };
}

async function commandList() {
  try {
    const all = await api.commands.getAll();
    return all.map(c => ({ name: c.name, description: c.description || (c.name === '_execute_action' ? t('cmd_open') : c.name), shortcut: c.shortcut || '' }));
  } catch {
    return [];
  }
}

async function optionsState() {
  const s = await getSettings();
  const cat = await getCatalogue();
  const { state, lastError } = await api.storage.local.get(['state', 'lastError']);
  await flushAllTabs();
  return {
    settings: s,
    catalogue: cat,
    state: state || null,
    lastError: lastError || null,
    version: api.runtime.getManifest().version,
    stats: summarizeStats(await loadStats()),
    commands: await commandList(),
  };
}

async function welcomeState() {
  const cat = await getCatalogue();
  const { state } = await api.storage.local.get('state');
  let incognito = null;
  try {
    if (api.extension && api.extension.isAllowedIncognitoAccess) incognito = await api.extension.isAllowedIncognitoAccess();
  } catch { /* unknown */ }
  return {
    version: api.runtime.getManifest().version,
    hostAccess: await hasAllSitesAccess(),
    incognito,
    enabledLists: (state && state.enabledLists) || [],
    lists: cat.lists.map(l => ({ id: l.id, name: l.name })),
    webkitRules: (state && state.webkitRules) || 0,
    listsBuilt: cat.generated,
    commands: await commandList(),
  };
}

function exportSettings(s) {
  const copy = JSON.parse(JSON.stringify(s));
  // timed pauses are temporary: leave them out so an import never makes them permanent
  const timed = Object.keys(copy.sitePauseUntil || {});
  copy.pausedSites = (copy.pausedSites || []).filter(site => !timed.includes(site));
  if (copy.paused && copy.pausedUntil) copy.paused = false;
  delete copy.pausedUntil;
  delete copy.sitePauseUntil;
  return { format: 'bouclier-settings', version: 1, app: api.runtime.getManifest().version, exportedAt: new Date().toISOString(), settings: copy };
}

const MAX_FILTER_CHARS = 200000;

/** Keeps the user's filters under the size limit, cut at the end of a line, never inside a filter. */
function capFilters(text) {
  text = String(text || '');
  if (text.length <= MAX_FILTER_CHARS) return { text, cut: false };
  const end = text.lastIndexOf('\n', MAX_FILTER_CHARS);
  return { text: text.slice(0, end > 0 ? end : 0), cut: true };
}

/** Keeps only known settings with the right types; returns null when the file is not a Bouclier backup. */
function sanitizeImport(data, cat) {
  const src = data && data.format === 'bouclier-settings' ? data.settings : null;
  if (!src || typeof src !== 'object') return null;
  const out = {};
  const bool = k => { if (typeof src[k] === 'boolean') out[k] = src[k]; };
  ['paused', 'youtube', 'youtubeHideShorts', 'popupBlocker', 'showBadge'].forEach(bool);
  if (out.paused) out.pausedUntil = 0;
  const ids = cat.lists.map(l => l.id);
  if (Array.isArray(src.enabledLists)) out.enabledLists = [...new Set(src.enabledLists.filter(id => ids.includes(id)))];
  if (Array.isArray(src.pausedSites)) out.pausedSites = [...new Set(src.pausedSites.filter(isHostname))].slice(0, 5000);
  if (typeof src.customFilters === 'string') out.customFilters = capFilters(src.customFilters).text;
  const cleanMap = (value, fn) => {
    const res = {};
    if (!value || typeof value !== 'object' || Array.isArray(value)) return res;
    for (const [k, v] of Object.entries(value)) {
      if (!isHostname(k)) continue;
      const cleaned = fn(v);
      if (cleaned) res[k] = cleaned;
    }
    return res;
  };
  if (src.pickedHides) {
    out.pickedHides = cleanMap(src.pickedHides, v => {
      if (!Array.isArray(v)) return null;
      const sels = v.filter(isSafeSelector);
      return sels.length ? sels : null;
    });
  }
  if (src.siteControls) {
    out.siteControls = cleanMap(src.siteControls, v => {
      if (!v || typeof v !== 'object') return null;
      const c = { fonts: v.fonts === true, scripts3p: v.scripts3p === true, comments: v.comments === true };
      return c.fonts || c.scripts3p || c.comments ? c : null;
    });
  }
  return out;
}

async function handleMessage(msg, sender) {
  switch (msg && msg.type) {
    case 'cosmetic':
      return injectCosmetics(msg, sender);
    case 'picker:add':
      return addPicked(msg, sender);
    case 'popup:state':
      return popupState(msg.tabId);
    case 'popup:toggleSite': {
      if (!msg.host) return { ok: false };
      return toggleSite(msg.host);
    }
    case 'popup:setSitePaused': {
      if (!msg.host || typeof msg.paused !== 'boolean') return { ok: false };
      return setSitePaused(msg.host, msg.paused);
    }
    case 'popup:pauseSiteFor': {
      // "Pause on this site for 1 hour / 2 hours / until tomorrow": the site is allowed, every other site stays blocked
      const host = String(msg.host || '').toLowerCase();
      const until = pauseEnd(msg);
      if (!hostnameOf('https://' + host) || !until) return { ok: false };
      if (!isHostname(host) || !isHostname(siteKey(host))) return { ok: false, error: cannotPause() };
      return update(s => {
        const site = pausedSiteFor(host, s.pausedSites) || siteKey(host);
        if (!s.pausedSites.includes(site)) s.pausedSites = s.pausedSites.concat(site);
        s.sitePauseUntil[site] = until;
      });
    }
    case 'popup:togglePause':
      return update(s => {
        s.paused = !s.paused;
        s.pausedUntil = 0;
      });
    case 'popup:setPause':
      // the popup says what the user chose, so a pause that ended while it was open is never flipped back on
      if (typeof msg.paused !== 'boolean') return { ok: false };
      return update(s => {
        if (s.paused === msg.paused && !s.pausedUntil) return false;
        s.paused = msg.paused;
        s.pausedUntil = 0;
      });
    case 'popup:pauseAllFor': {
      const until = pauseEnd(msg);
      if (!until) return { ok: false };
      return update(s => {
        s.paused = true;
        s.pausedUntil = until;
      });
    }
    case 'popup:toggleList':
      return update(s => {
        const on = s.enabledLists.includes(msg.id);
        s.enabledLists = on ? s.enabledLists.filter(id => id !== msg.id) : s.enabledLists.concat(msg.id);
      });
    case 'popup:toggleYoutube':
      return update(s => { s.youtube = !s.youtube; });
    case 'popup:setOption':
    case 'options:setOption': {
      const allowed = ['youtubeHideShorts', 'popupBlocker', 'showBadge', 'youtube'];
      if (!allowed.includes(msg.key) || typeof msg.value !== 'boolean') return { ok: false };
      return update(s => { s[msg.key] = msg.value; });
    }
    case 'popup:siteControl': {
      const host = String(msg.host || '');
      const key = msg.key;
      if (!hostnameOf('https://' + host) || !['fonts', 'scripts3p', 'comments'].includes(key)) return { ok: false };
      return update(s => {
        const site = siteKey(host);
        const c = Object.assign({ fonts: false, scripts3p: false, comments: false }, s.siteControls[site]);
        c[key] = !!msg.value;
        if (c.fonts || c.scripts3p || c.comments) s.siteControls[site] = c;
        else delete s.siteControls[site];
      });
    }
    case 'popup:allowHost': {
      // "Allow this address on this site": a custom @@ filter scoped to the site
      const host = String(msg.host || '').toLowerCase();
      const site = siteKey(String(msg.site || '').toLowerCase());
      if (!isHostname(host) || !isHostname(site)) return { ok: false };
      const line = `@@||${host}^$domain=${site}`;
      return update(s => {
        const lines = String(s.customFilters || '').split(/\r?\n/);
        if (!lines.includes(line)) s.customFilters = (s.customFilters ? s.customFilters.replace(/\s*$/, '\n') : '') + line;
      });
    }
    case 'popup:unhideSite': {
      const site = siteKey(String(msg.host || ''));
      return update(s => { delete s.pickedHides[site]; });
    }
    case 'popup:picker':
      return startPicker(msg.tabId, {});
    case 'popup:command':
      return runCommand(msg.command);
    case 'options:state':
      return optionsState();
    case 'options:saveCustom': {
      const { text, cut } = capFilters(msg.text);
      const res = await update(s => { s.customFilters = text; });
      const { state } = await api.storage.local.get('state');
      const errors = ((state && state.customErrors) || parseCustomFilters(text).errors).slice();
      if (cut) errors.unshift(t('bg_filter_cut', formatNumber(MAX_FILTER_CHARS)));
      return Object.assign({}, res, { errors, kept: text.length });
    }
    case 'options:removeSite':
      return update(s => {
        s.pausedSites = s.pausedSites.filter(x => x !== msg.site);
        delete s.sitePauseUntil[msg.site];
      });
    case 'options:removePicked':
      return update(s => {
        const left = (s.pickedHides[msg.host] || []).filter(x => x !== msg.selector);
        if (left.length) s.pickedHides[msg.host] = left;
        else delete s.pickedHides[msg.host];
      });
    case 'options:removeSiteControls':
      return update(s => { delete s.siteControls[msg.site]; });
    case 'options:export':
      return { ok: true, data: exportSettings(await getSettings()) };
    case 'options:import': {
      const cat = await getCatalogue();
      const clean = sanitizeImport(msg.data, cat);
      if (!clean) return { ok: false, error: t('bg_import_not_backup') };
      const res = await update(s => { Object.assign(s, clean); });
      return Object.assign({}, res, { imported: Object.keys(clean) });
    }
    case 'options:resetStats':
      await statsTask(() => api.storage.local.set({ stats: { since: Date.now(), days: {}, sites: {} } }));
      return { ok: true };
    case 'options:reset':
      // inside the settings queue, so a change arriving at the same moment cannot write old settings back
      await mutateSettings(async () => {
        await api.storage.local.remove(['settings', 'dnrSignature', 'csSignature']);
        return false;
      });
      return applySettings({ force: true });
    case 'welcome:state':
      return welcomeState();
    case 'debug:compat':
      // lets the test-suite check the Safari-before-26 rulesets in Chromium
      compatOverride = typeof msg.on === 'boolean' ? msg.on : null;
      return applySettings({ force: true });
    case 'debug:purge': {
      // lets the test-suite fast-forward timed pauses
      let changed = false;
      await mutateSettings(s => (changed = purgeExpired(s, Number(msg.now) || Date.now())));
      if (changed) await applySettings();
      return { ok: true, settings: await getSettings() };
    }
    default:
      return { ok: false, error: 'unknown message' };
  }
}

const EXTENSION_ORIGIN = api.runtime.getURL('');
const PAGE_MESSAGES = new Set(['cosmetic', 'picker:add']);

/** Returns the message to handle, or null when this sender may not send it. */
const isExtensionPage = sender => typeof sender.url === 'string' && sender.url.startsWith(EXTENSION_ORIGIN);

function gateMessage(msg, sender) {
  if (sender.id && sender.id !== api.runtime.id) return null;
  if (isExtensionPage(sender)) return msg;
  // web pages only reach us through our content script: allow just what it needs,
  // and trust the frame's real address over anything in the message
  if (!msg || !PAGE_MESSAGES.has(msg.type)) return null;
  return sender.url ? Object.assign({}, msg, { url: sender.url }) : msg;
}

api.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  const allowed = gateMessage(msg, sender);
  if (!allowed) return false;
  // The menu asks for its page to be reloaded once a change is applied. Done here rather than in the
  // menu: Safari recompiles every rule for a change (several seconds), and the menu may be closed by then.
  const reloadTabId = isExtensionPage(sender) && Number.isInteger(allowed.reloadTabId) ? allowed.reloadTabId : null;
  handleMessage(allowed, sender)
    .then(async res => {
      if (reloadTabId !== null && !(res && res.ok === false)) {
        try { await api.tabs.reload(reloadTabId); } catch { /* the tab was closed */ }
      }
      return res;
    })
    .then(sendResponse, err => sendResponse({ ok: false, error: String(err && err.message || err) }));
  return true;
});

/* --------------------------------------------------- tabs and alarms */

api.tabs.onUpdated.addListener(async (tabId, info, tab) => {
  if (info.url && popupWatch.has(tabId)) checkPopup(tabId, info.url);
  if (info.status === 'loading' && info.url && !hasWebNavigation) {
    onNavigationStart(tabId, info.url, Date.now());
  } else if (info.status === 'complete') {
    scheduleFlush();
  }
  if (info.url || info.status === 'loading') {
    try {
      const s = await getSettings();
      refreshTabIcon(tab, s);
      if (tab && tab.active) refreshSiteMenu(tab, s);
    } catch { /* worker shutting down */ }
  }
});

api.tabs.onActivated.addListener(async ({ tabId }) => {
  try {
    const tab = await api.tabs.get(tabId);
    const s = await getSettings();
    refreshTabIcon(tab, s);
    refreshSiteMenu(tab, s);
  } catch { /* ignore */ }
});

if (api.windows && api.windows.onFocusChanged) {
  api.windows.onFocusChanged.addListener(() => { refreshSiteMenu(null); });
}

api.tabs.onRemoved.addListener(tabId => {
  popupWatch.delete(tabId);
  flushAll().then(() => statsTask(async () => {
    // keep the high-water mark: Safari and Chromium report a closed tab's blocks for a while
    const tabs = await getTabState();
    const tabInfo = tabs[tabId] || {};
    tabs[tabId] = { last: Math.max(tabInfo.last || 0, Date.now()), closedAt: Date.now() };
    await session.set('tabState', tabs);
  }));
});

if (api.alarms) {
  api.alarms.onAlarm.addListener(async alarm => {
    if (alarm.name === 'stats-flush') {
      await flushAllTabs();
      return;
    }
    if (alarm.name === 'resume-all' || alarm.name.startsWith('resume-site|')) {
      let changed = false;
      await mutateSettings(s => (changed = purgeExpired(s, Date.now() + 1000)));
      if (changed) {
        await applySettings();
        const tab = await activeTab().catch(() => null);
        if (tab && hostnameOf(tab.url || '')) {
          try {
            await api.tabs.sendMessage(tab.id, { type: 'toast', text: t('bg_toast_protecting_again') }, { frameId: 0 });
          } catch { /* ignore */ }
        }
      }
    }
  });
}

/* ---------------------------------------------------------- lifecycle */

let initChain = Promise.resolve();
let installed = false;
function init(reason) {
  if (reason === 'install' || reason === 'update') installed = true;
  initChain = initChain.then(async () => {
    if (reason === 'first-wake' && installed) return;   // onInstalled already did everything
    await setupMenus();
    await applySettings({ force: reason === 'install' || reason === 'update' });
    if (reason === 'install') {
      try { await api.tabs.create({ url: api.runtime.getURL('pages/welcome.html') }); } catch { /* ignore */ }
    }
  }).catch(err => console.warn('Bouclier init', err));
  return initChain;
}

api.runtime.onInstalled.addListener(details => { init(details.reason); });
api.runtime.onStartup.addListener(() => { init('startup'); });
// The worker is also woken by page messages; only do the full sync if it never ran.
api.storage.local.get('state').then(({ state }) => {
  // give onInstalled (fired right after a first install) a moment to claim the first run
  if (!state) setTimeout(() => init('first-wake'), 250);
}).catch(() => {});
