/*
 * Bouclier – which device the extension's pages are shown on.
 * Loaded first by the toolbar menu, the settings page and the welcome page.
 * Adds "mac" or "ios" plus "mac" / "iphone" / "ipad" to <html>, so the CSS can
 * show the right instructions ([data-only="mac"] / [data-only="ios"]), and sets
 * globalThis.BOUCLIER_PLATFORM for the scripts.
 */
(() => {
  'use strict';
  const ua = navigator.userAgent || '';
  // iPadOS asks for desktop sites, so an iPad reports "Macintosh" and has a touch screen.
  const touchMac = /Macintosh/.test(ua) && navigator.maxTouchPoints > 1;
  const kind = /iPhone|iPod/.test(ua) ? 'iphone' : (/iPad/.test(ua) || touchMac) ? 'ipad' : 'mac';
  const ios = kind !== 'mac';
  document.documentElement.classList.add(ios ? 'ios' : 'mac');
  if (ios) document.documentElement.classList.add(kind);
  globalThis.BOUCLIER_PLATFORM = Object.freeze({
    kind,
    ios,
    device: kind === 'iphone' ? 'this iPhone' : kind === 'ipad' ? 'this iPad' : 'this Mac',
    extensionSettings: ios ? 'Settings → Apps → Safari → Extensions → Bouclier' : 'Safari Settings → Extensions → Bouclier',
  });
})();
