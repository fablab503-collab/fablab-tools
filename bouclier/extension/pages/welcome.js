'use strict';
const api = globalThis.browser ?? globalThis.chrome;
const $ = id => document.getElementById(id);
const P = globalThis.BOUCLIER_PLATFORM || { ios: false, device: 'this Mac', extensionSettings: 'Safari Settings → Extensions → Bouclier' };
const I = globalThis.BOUCLIER_I18N || { t: key => key, lang: () => 'en', locale: () => undefined, apply: () => {} };
const t = I.t;
const STALE_DAYS = 45; // App Store updates bring new lists; warn when one is overdue

I.apply();

function item(ok, title, desc, action) {
  const li = document.createElement('li');
  li.className = ok === true ? 'ok' : ok === false ? 'todo' : 'unknown';
  const dot = document.createElement('span');
  dot.className = 'dot';
  dot.setAttribute('role', 'img');
  dot.setAttribute('aria-label', ok === true ? t('welcome_state_done') : ok === false ? t('welcome_state_attention') : t('welcome_state_unknown'));
  const body = document.createElement('div');
  body.className = 'body';
  const ti = document.createElement('div');
  ti.className = 'title';
  ti.textContent = title;
  const d = document.createElement('div');
  d.className = 'desc';
  d.textContent = desc;
  body.append(ti, d);
  if (action) {
    const b = document.createElement('button');
    b.textContent = action.label;
    b.addEventListener('click', action.run);
    body.append(b);
  }
  li.append(dot, body);
  return li;
}

/** A filter list's name in the device's language (the catalogue's English name when there is no translation). */
function listName(l) {
  const s = t(`list_${l.id}_name`);
  return s === `list_${l.id}_name` ? l.name : s;
}

async function check() {
  const st = await api.runtime.sendMessage({ type: 'welcome:state' });
  $('version-line').textContent = t('welcome_version_line', st.version, new Date(st.listsBuilt * 1000).toLocaleDateString(I.locale()));
  const ul = $('checks');
  ul.textContent = '';
  ul.append(item(true, t('welcome_on_title'), t('welcome_on_desc')));
  const steps = P.accessSteps || (P.ios ? `In ${P.extensionSettings}, set All Websites to Allow.` : '');
  ul.append(item(st.hostAccess, st.hostAccess ? t('welcome_access_ok_title') : t('welcome_access_todo_title'),
    st.hostAccess
      ? t('welcome_access_ok_desc')
      : t('welcome_access_todo_desc') + ' ' + (P.ios ? steps : t('welcome_access_when_asked', steps)),
    st.hostAccess ? null : {
      label: t('welcome_access_button'),
      run: async () => {
        // Safari only asks during a click, so the request comes first; the check below shows the result
        try { await api.permissions.request({ origins: ['<all_urls>'] }); } catch { /* not asked: the steps above */ }
        recheck();
      },
    }));
  const names = st.lists.filter(l => st.enabledLists.includes(l.id)).map(listName);
  ul.append(item(names.length > 0, names.length ? t('welcome_lists_title', names.length) : t('welcome_lists_none_title'),
    names.length ? t('welcome_lists_desc', names.join(', '), st.webkitRules.toLocaleString(I.locale())) : t('welcome_lists_none_desc')));
  if (st.incognito === true || st.incognito === false) {
    ul.append(item(st.incognito, st.incognito ? t('welcome_private_ok_title') : t('welcome_private_off_title'),
      st.incognito ? t('welcome_private_ok_desc') : t(P.ios ? 'welcome_private_off_desc_ios' : 'welcome_private_off_desc_mac', P.extensionSettings)));
  }
  const ageDays = Math.floor((Date.now() / 1000 - st.listsBuilt) / 86400);
  const fresh = ageDays <= STALE_DAYS;
  ul.append(item(fresh, fresh ? t('welcome_fresh_title') : t('welcome_stale_title', ageDays),
    fresh ? (ageDays === 0 ? t('welcome_built_today') : ageDays === 1 ? t('welcome_built_yesterday') : t('welcome_built_days', ageDays)) : t('welcome_stale_desc')));
  const cmds = (st.commands || []).filter(c => c.shortcut);
  if (cmds.length) $('shortcuts').textContent = cmds.map(c => `${c.shortcut} ${c.description.toLowerCase()}`).join(', ') + '.';
}

function recheck() {
  return check().catch(err => { $('checks').textContent = t('welcome_check_failed', err && err.message || err); });
}

$('recheck').addEventListener('click', recheck);
recheck();
