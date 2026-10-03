/*
 * Bouclier – page helper (isolated world, every frame, document_start).
 *   1. asks the background to inject this page's site-specific element hiding;
 *   2. runs the "Hide an element" picker when the popup asks for it.
 */
(() => {
  'use strict';
  if (globalThis.__bouclierPage) return;
  globalThis.__bouclierPage = true;

  const api = globalThis.browser ?? globalThis.chrome;

  // Bouclier's texts in the device's language (i18n/picker.json), with {0}, {1}… replaced by subs.
  // Pages have common/i18n.js; this script runs inside web pages, so it has its own copy.
  function t(key, ...subs) {
    let s = '';
    try { s = api.i18n.getMessage(key) || ''; } catch { /* no i18n */ }
    if (!s) return key;
    return subs.length ? s.replace(/\{(\d)\}/g, (m, i) => (subs[i] !== undefined ? String(subs[i]) : m)) : s;
  }

  const request = () => {
    try {
      const p = api.runtime.sendMessage({ type: 'cosmetic', url: location.href });
      if (p && typeof p.catch === 'function') p.catch(() => {});
    } catch { /* extension reloaded */ }
  };
  if (/^https?:$/.test(location.protocol)) request();

  // Remember what was right-clicked, for the "Hide This Element…" menu item.
  let contextTarget = null;
  document.addEventListener('contextmenu', e => { contextTarget = e.target; }, true);

  api.runtime.onMessage.addListener(msg => {
    if (!msg) return;
    if (msg.type === 'picker:start') {
      if (msg.preselect) {
        let target = null;
        if (msg.frameUrl && window === window.top) {
          // the click was inside a frame this page embeds: offer to hide the whole frame
          target = [...document.querySelectorAll('iframe, frame')].find(f => f.src === msg.frameUrl) || null;
        } else if (contextTarget && contextTarget.isConnected) {
          target = contextTarget;
        }
        startPicker(target);
      } else if (window === window.top) {
        startPicker(null);
      }
    } else if (msg.type === 'toast' && window === window.top) {
      showToast(String(msg.text || ''));
    }
  });

  /* -------------------------------------------------------------- toast */

  function showToast(text) {
    if (!text || !document.documentElement) return;
    const host = document.createElement('bouclier-toast');
    host.style.cssText = 'all:initial;position:fixed;top:14px;right:14px;z-index:2147483647;pointer-events:none;';
    const root = host.attachShadow({ mode: 'closed' });
    root.innerHTML = `
      <style>
        .t { font: 13px/1.35 -apple-system, BlinkMacSystemFont, "Helvetica Neue", sans-serif; color: #fff;
             background: rgba(28,28,30,.94); border-radius: 12px; padding: 9px 13px; max-width: 320px;
             box-shadow: 0 6px 24px rgba(0,0,0,.3); display: flex; gap: 8px; align-items: center;
             opacity: 0; transform: translateY(-6px); transition: opacity .2s, transform .2s; }
        .t.on { opacity: 1; transform: none; }
        .dot { width: 8px; height: 8px; border-radius: 50%; background: #0a84ff; flex: 0 0 auto; }
      </style>
      <div class="t"><span class="dot"></span><span class="msg"></span></div>`;
    root.querySelector('.msg').textContent = text;
    document.documentElement.appendChild(host);
    const box = root.querySelector('.t');
    requestAnimationFrame(() => box.classList.add('on'));
    setTimeout(() => {
      box.classList.remove('on');
      setTimeout(() => host.remove(), 250);
    }, 3500);
  }

  /* ------------------------------------------------------------- picker */

  let picker = null;

  function isStable(token) {
    return token && token.length <= 40 && !/\d{3,}|[a-f0-9]{8,}|^[a-z]{1,2}\d|--|__[a-z0-9]{6,}|:/i.test(token);
  }

  function uniqueEnough(sel) {
    try {
      return document.querySelectorAll(sel).length;
    } catch {
      return 0;
    }
  }

  function selectorFor(el) {
    const esc = s => (window.CSS && CSS.escape ? CSS.escape(s) : s.replace(/[^\w-]/g, '\\$&'));
    if (el.id && isStable(el.id) && uniqueEnough('#' + esc(el.id)) === 1) return '#' + esc(el.id);
    const parts = [];
    let node = el;
    while (node && node.nodeType === 1 && node !== document.body && node !== document.documentElement && parts.length < 6) {
      if (node.id && isStable(node.id)) {
        parts.unshift('#' + esc(node.id));
        break;
      }
      let part = node.localName;
      const classes = [...node.classList].filter(isStable).slice(0, 3);
      part += classes.map(c => '.' + esc(c)).join('');
      const parent = node.parentElement;
      if (parent) {
        const sameType = [...parent.children].filter(c => c.localName === node.localName);
        const sameSel = sameType.filter(c => classes.every(k => c.classList.contains(k)));
        if (sameSel.length > 1) part += `:nth-of-type(${sameType.indexOf(node) + 1})`;
      }
      parts.unshift(part);
      const sel = parts.join(' > ');
      if (uniqueEnough(sel) === 1) return sel;
      node = parent;
    }
    return parts.join(' > ');
  }

  function startPicker(initial) {
    if (picker) {
      if (initial) picker.choose(initial);
      return;
    }
    const host = document.createElement('bouclier-picker');
    host.style.cssText = 'all:initial;position:fixed;inset:0;z-index:2147483647;pointer-events:none;';
    const root = host.attachShadow({ mode: 'closed' });
    root.innerHTML = `
      <style>
        :host { all: initial; }
        .box { position: fixed; pointer-events: none; border: 2px solid #0a84ff; background: rgba(10,132,255,.18);
               border-radius: 4px; transition: all .06s ease-out; display: none; }
        .bar { position: fixed; left: 50%; bottom: 20px; transform: translateX(-50%); pointer-events: auto;
               font: 13px/1.35 -apple-system, BlinkMacSystemFont, "Helvetica Neue", sans-serif; color: #fff;
               background: rgba(28,28,30,.94); border-radius: 14px; padding: 10px 12px; display: flex; gap: 8px;
               align-items: center; box-shadow: 0 8px 30px rgba(0,0,0,.35); max-width: min(92vw, 720px); }
        .msg { flex: 1; min-width: 0; }
        .sel { display: block; font: 11px ui-monospace, Menlo, monospace; color: #9ecbff; white-space: nowrap;
               overflow: hidden; text-overflow: ellipsis; margin-top: 2px; }
        button { font: inherit; border: 0; border-radius: 9px; padding: 6px 11px; cursor: pointer; color: #fff;
                 background: rgba(255,255,255,.14); }
        button.primary { background: #0a84ff; }
        button:disabled { opacity: .4; cursor: default; }
        @media (pointer: coarse) {
          .bar { font-size: 15px; padding: 12px 14px; bottom: max(20px, env(safe-area-inset-bottom)); }
          .sel { font-size: 12px; }
          button { padding: 9px 13px; }
        }
        @media (max-width: 560px) {
          .bar { flex-wrap: wrap; left: 12px; right: 12px; transform: none; max-width: none; }
          .msg { flex: 1 1 100%; }
          button { flex: 1; }
        }
      </style>
      <div class="box"></div>
      <div class="bar">
        <div class="msg"><span class="title"></span><span class="sel"></span></div>
        <button class="wider" disabled></button>
        <button class="hide primary" disabled></button>
        <button class="cancel"></button>
      </div>`;
    document.documentElement.appendChild(host);
    const box = root.querySelector('.box');
    const title = root.querySelector('.title');
    const selText = root.querySelector('.sel');
    const btnWider = root.querySelector('.wider');
    const btnHide = root.querySelector('.hide');
    const btnCancel = root.querySelector('.cancel');
    title.textContent = t('picker_click_to_hide');
    btnWider.textContent = t('picker_wider');
    btnWider.title = t('picker_wider_hint');
    btnHide.textContent = t('picker_hide');
    btnCancel.textContent = t('picker_cancel');
    let hovered = null;
    let chosen = null;

    const outline = el => {
      if (!el) {
        box.style.display = 'none';
        return;
      }
      const r = el.getBoundingClientRect();
      Object.assign(box.style, { display: 'block', left: r.left + 'px', top: r.top + 'px', width: r.width + 'px', height: r.height + 'px' });
    };
    const choose = el => {
      chosen = el;
      outline(el);
      const sel = selectorFor(el);
      chosen.__sel = sel;
      title.textContent = t('picker_hide_this', el.localName, location.hostname);
      selText.textContent = sel;
      btnHide.disabled = false;
      btnWider.disabled = !el.parentElement || el.parentElement === document.body;
    };
    const onMove = e => {
      if (chosen) return;
      const el = document.elementFromPoint(e.clientX, e.clientY);
      if (!el || el === host || el === document.documentElement || el === document.body) return;
      hovered = el;
      outline(el);
    };
    const pickable = el => el && el !== host && el !== document.documentElement && el !== document.body;
    const onClick = e => {
      if (e.composedPath().includes(host)) return;
      e.preventDefault();
      e.stopPropagation();
      // no hover on a touch screen: take what is under the finger
      const el = chosen || !hovered ? document.elementFromPoint(e.clientX, e.clientY) : hovered;
      if (pickable(el)) choose(el);
    };
    // iPhone and iPad: a tap on plain text or an image sends no click to the page, so taps are read
    // from the touch events (a finger that moved is scrolling, not choosing).
    let touchStart = null;
    const onTouchStart = e => {
      const touch = e.touches[0];
      touchStart = e.touches.length === 1 && !e.composedPath().includes(host) ? { x: touch.clientX, y: touch.clientY } : null;
    };
    const onTouchEnd = e => {
      const start = touchStart;
      touchStart = null;
      const touch = e.changedTouches[0];
      if (!start || !touch || e.composedPath().includes(host)) return;
      if (Math.hypot(touch.clientX - start.x, touch.clientY - start.y) > 10) return;
      e.preventDefault(); // no click follows, so links and buttons under the finger stay put
      e.stopPropagation();
      const el = document.elementFromPoint(touch.clientX, touch.clientY);
      if (pickable(el)) choose(el);
    };
    const onKey = e => {
      if (e.key === 'Escape') stop();
    };
    const stop = () => {
      document.removeEventListener('mousemove', onMove, true);
      document.removeEventListener('click', onClick, true);
      document.removeEventListener('keydown', onKey, true);
      document.removeEventListener('touchstart', onTouchStart, true);
      document.removeEventListener('touchend', onTouchEnd, true);
      window.removeEventListener('scroll', onScroll, true);
      host.remove();
      picker = null;
    };
    const onScroll = () => outline(chosen || hovered);
    btnWider.addEventListener('click', e => {
      e.stopPropagation();
      if (chosen && chosen.parentElement && chosen.parentElement !== document.body) choose(chosen.parentElement);
    });
    btnCancel.addEventListener('click', e => {
      e.stopPropagation();
      stop();
    });
    btnHide.addEventListener('click', e => {
      e.stopPropagation();
      if (!chosen) return;
      const sel = chosen.__sel;
      try {
        document.querySelectorAll(sel).forEach(node => node.style.setProperty('display', 'none', 'important'));
      } catch { /* invalid selector */ }
      try {
        const p = api.runtime.sendMessage({ type: 'picker:add', url: location.href, selector: sel });
        if (p && p.catch) p.catch(() => {});
      } catch { /* ignore */ }
      stop();
    });
    document.addEventListener('mousemove', onMove, true);
    document.addEventListener('click', onClick, true);
    document.addEventListener('keydown', onKey, true);
    document.addEventListener('touchstart', onTouchStart, { capture: true, passive: true });
    document.addEventListener('touchend', onTouchEnd, { capture: true, passive: false });
    window.addEventListener('scroll', onScroll, true);
    picker = { stop, choose };
    if (initial && initial.nodeType === 1 && initial !== document.body && initial !== document.documentElement) choose(initial);
  }
})();
