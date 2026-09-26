// adblock.turtlecute.org: its own verdict per address, next to what resource timing saw
(() => {
  const seen = new Set(performance.getEntriesByType('resource').map(e => { try { return new URL(e.name).hostname } catch { return '' } }));
  const timing = {};
  for (const e of performance.getEntriesByType('resource')) { try { const h = new URL(e.name).hostname; timing[h] = [Math.round(e.duration), e.transferSize, e.nextHopProtocol || '-', e.responseStatus ?? '?'] } catch {} }
  const items = [...document.querySelectorAll('li, div')].filter(n => n.children.length <= 3 && /^[a-z0-9.-]+\.[a-z]{2,}$/i.test((n.innerText || '').trim()));
  const rows = [];
  for (const n of items) {
    const host = n.innerText.trim();
    const html = n.outerHTML;
    const cls = (n.className || '') + ' ' + [...n.querySelectorAll('*')].map(x => x.getAttribute('class') || '').join(' ');
    rows.push([host, /blocked|success|green|check/i.test(cls) ? 'B' : /fail|danger|red|x-circle|error/i.test(cls) ? 'N' : '?', seen.has(host) ? 'in-timing' : '-', JSON.stringify(timing[host] || null), html.length < 400 ? html : html.slice(0, 200)]);
  }
  const uniq = {}; for (const r of rows) uniq[r[0]] = r;
  return JSON.stringify(Object.values(uniq));
})()
