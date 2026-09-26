'use strict';
const api = globalThis.browser ?? globalThis.chrome;
const $ = id => document.getElementById(id);
const P = globalThis.BOUCLIER_PLATFORM || { ios: false, device: 'this Mac', extensionSettings: 'Safari Settings → Extensions → Bouclier' };
const STALE_DAYS = 45; // App Store updates bring new lists; warn when one is overdue

function item(ok, title, desc, action) {
  const li = document.createElement('li');
  li.className = ok === true ? 'ok' : ok === false ? 'todo' : 'unknown';
  const dot = document.createElement('span');
  dot.className = 'dot';
  dot.setAttribute('aria-label', ok === true ? 'Done' : ok === false ? 'Needs attention' : 'Unknown');
  const body = document.createElement('div');
  body.className = 'body';
  const t = document.createElement('div');
  t.className = 'title';
  t.textContent = title;
  const d = document.createElement('div');
  d.className = 'desc';
  d.textContent = desc;
  body.append(t, d);
  if (action) {
    const b = document.createElement('button');
    b.textContent = action.label;
    b.addEventListener('click', action.run);
    body.append(b);
  }
  li.append(dot, body);
  return li;
}

async function check() {
  const st = await api.runtime.sendMessage({ type: 'welcome:state' });
  $('version-line').textContent = `Version ${st.version} · filter lists built ${new Date(st.listsBuilt * 1000).toLocaleDateString()}`;
  const ul = $('checks');
  ul.textContent = '';
  ul.append(item(true, 'Bouclier is turned on in Safari', 'You are reading a page that only exists inside the extension, so Safari is running it.'));
  ul.append(item(st.hostAccess, st.hostAccess ? 'Allowed on every website' : 'Not allowed on every website yet',
    st.hostAccess
      ? 'Ad spaces are hidden, pop-ups closed and YouTube ads removed on every site.'
      : 'Blocking already works, but hiding ad spaces, closing pop-ups and YouTube ad removal need access to websites. ' +
        (P.ios ? `In ${P.extensionSettings}, set All Websites to Allow.` : 'Choose "Always Allow on Every Website" when Safari asks.'),
    st.hostAccess ? null : {
      label: 'Allow on every website',
      run: async () => {
        try { await api.permissions.request({ origins: ['<all_urls>'] }); } catch { /* Safari shows its own prompt */ }
        check();
      },
    }));
  const names = st.lists.filter(l => st.enabledLists.includes(l.id)).map(l => l.name);
  ul.append(item(names.length > 0, names.length ? `${names.length} filter lists active` : 'No filter list is active',
    names.length ? `${names.join(', ')}. ${st.webkitRules.toLocaleString()} of Safari's 150,000 rules in use.` : 'Open the toolbar shield and turn on Ads and Trackers.'));
  if (st.incognito === true || st.incognito === false) {
    ul.append(item(st.incognito, st.incognito ? 'Works in Private Browsing' : 'Off in Private Browsing',
      st.incognito ? 'Private Browsing is protected too.' : `To protect Private Browsing: ${P.extensionSettings} → ${P.ios ? 'turn on' : 'tick'} "Allow in Private Browsing".`));
  }
  const ageDays = Math.floor((Date.now() / 1000 - st.listsBuilt) / 86400);
  const fresh = ageDays <= STALE_DAYS;
  ul.append(item(fresh, fresh ? 'Filter lists are recent' : `Filter lists are ${ageDays} days old`,
    fresh ? `Built ${ageDays === 0 ? 'today' : ageDays === 1 ? 'yesterday' : ageDays + ' days ago'}.` : 'Check the App Store for a Bouclier update: each one brings fresh lists.'));
  const cmds = (st.commands || []).filter(c => c.shortcut);
  if (cmds.length) $('shortcuts').textContent = cmds.map(c => `${c.shortcut} ${c.description.toLowerCase()}`).join(', ') + '.';
}

$('recheck').addEventListener('click', check);
check().catch(err => { $('checks').textContent = 'Could not run the check: ' + err.message; });
