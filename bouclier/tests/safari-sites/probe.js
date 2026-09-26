// Runs in the tested page (Safari "do JavaScript"). Returns one JSON string describing the page.
// Read-only: it looks at the page, it never clicks or changes anything.
(() => {
  const out = { href: location.href, title: document.title.slice(0, 120), ready: document.readyState };
  const body = document.body;
  const text = body ? body.innerText : '';
  out.textLength = text.length;
  out.imagesLoaded = [...document.images].filter(i => i.complete && i.naturalWidth > 0).length;
  out.height = document.documentElement.scrollHeight;

  // every request the page made that Safari did not block (blocked ones never start)
  const res = performance.getEntriesByType('resource');
  out.requests = res.length;
  out.res = res.slice(0, 1500).map(e => [e.name.slice(0, 400), e.initiatorType]);

  // ad slots still visible (EasyList's usual containers)
  const AD_SLOTS = [
    'ins.adsbygoogle', '[id^="google_ads_iframe"]', '[id^="div-gpt-ad"]', '[data-google-query-id]',
    'iframe[src*="doubleclick.net"]', 'iframe[src*="googlesyndication.com"]', 'iframe[src*="amazon-adsystem.com"]',
    '[id^="taboola-"]', '.trc_related_container', '.OUTBRAIN', '[data-ad-slot]', '[data-adunit]',
    '.ad-slot', '.adslot', '[id^="ad-slot"]', '[class*="dfp-ad"]', '[id^="dfp-"]', '.pub-container', '[id^="sas_"]',
  ].join(',');
  const visible = el => {
    const r = el.getBoundingClientRect();
    if (r.width < 30 || r.height < 30) return false;
    for (let n = el; n && n.nodeType === 1; n = n.parentElement) {
      const cs = getComputedStyle(n);
      if (cs.display === 'none' || cs.visibility === 'hidden' || cs.opacity === '0') return false;
    }
    return true;
  };
  const slots = [...document.querySelectorAll(AD_SLOTS)];
  out.adSlots = slots.length;
  out.adVisible = slots.filter(visible).slice(0, 12).map(el => {
    const r = el.getBoundingClientRect();
    return `${el.tagName.toLowerCase()}${el.id ? '#' + el.id : ''}${typeof el.className === 'string' && el.className ? '.' + el.className.trim().split(/\s+/).slice(0, 2).join('.') : ''} ${Math.round(r.width)}x${Math.round(r.height)}`.slice(0, 120);
  });
  out.iframes = [...document.querySelectorAll('iframe')].filter(visible).slice(0, 15).map(f => (f.src || '(no src)').slice(0, 160));

  // consent pop-up (ads often wait for it) and "turn off your ad blocker" messages
  const CMP = { didomi: '#didomi-host,#didomi-popup', onetrust: '#onetrust-banner-sdk,#onetrust-consent-sdk', sourcepoint: '[id^="sp_message_container"]',
    quantcast: '#qc-cmp2-container', funding_choices: '.fc-consent-root,.fc-ab-root', sirdata: '#sd-cmp', axeptio: '#axeptio_overlay', cookiebot: '#CybotCookiebotDialog', usercentrics: '#usercentrics-root', trustarc: '#truste-consent-track' };
  out.cmp = Object.keys(CMP).filter(k => [...document.querySelectorAll(CMP[k])].some(visible));
  out.tcf = typeof window.__tcfapi === 'function';
  const wall = text.match(/[^.\n]{0,60}(ad ?-?blocker|adblock|bloqueur de pub\w*|bloqueurs? de publicit\w*|d[ée]sactiv\w+ (?:votre|le) bloqueur|allow ?list|whitelist)[^.\n]{0,60}/i);
  out.adblockText = wall ? wall[0].trim().slice(0, 160) : null;
  const fc = document.querySelector('.fc-ab-root');
  out.adblockWall = !!(fc && visible(fc));

  // what covers the middle of the screen (a big overlay can mean a wall or a broken page)
  const mid = document.elementFromPoint(innerWidth / 2, innerHeight / 2);
  if (mid) {
    let n = mid, fixed = null;
    for (; n && n.nodeType === 1; n = n.parentElement) if (getComputedStyle(n).position === 'fixed') fixed = n;
    if (fixed) {
      const r = fixed.getBoundingClientRect();
      if (r.width * r.height > innerWidth * innerHeight * 0.5) out.overlay = `${fixed.tagName.toLowerCase()}${fixed.id ? '#' + fixed.id : ''}${typeof fixed.className === 'string' && fixed.className ? '.' + fixed.className.trim().split(/\s+/)[0] : ''}`.slice(0, 100);
    }
  }
  return JSON.stringify(out);
})()
