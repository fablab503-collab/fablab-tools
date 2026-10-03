/*
 * Bouclier – which device the extension's pages are shown on.
 * Loaded first by the toolbar menu, the settings page and the welcome page (after common/i18n.js).
 * Adds "mac" or "ios" plus "mac" / "iphone" / "ipad" to <html>, so the CSS can
 * show the right instructions ([data-only="mac"] / [data-only="ios"]), and sets
 * globalThis.BOUCLIER_PLATFORM for the scripts, in the device's language (English or French).
 */
(() => {
  'use strict';
  const I = globalThis.BOUCLIER_I18N;
  // the translated text, or the English one when the texts are not there
  const tr = (key, english, ...subs) => {
    const s = I ? I.t(key, ...subs) : key;
    return s === key ? english : s;
  };
  const ua = navigator.userAgent || '';
  // iPadOS asks for desktop sites, so an iPad reports "Macintosh" and has a touch screen.
  const touchMac = /Macintosh/.test(ua) && navigator.maxTouchPoints > 1;
  const kind = /iPhone|iPod/.test(ua) ? 'iphone' : (/iPad/.test(ua) || touchMac) ? 'ipad' : 'mac';
  const ios = kind !== 'mac';
  // iOS 18 moved Safari under Settings → Apps; iOS 16 and 17 list it directly in Settings.
  // (An iPad asking for desktop sites reports no iOS version, so it gets the newer path.)
  const iosVersion = (() => { const m = ua.match(/(?:iPhone|CPU) OS (\d+)[_.]/); return m ? Number(m[1]) : null; })();
  document.documentElement.classList.add(ios ? 'ios' : 'mac');
  if (ios) document.documentElement.classList.add(kind);
  const extensionSettings = !ios
    ? tr('platform_settings_mac', 'Safari Settings → Extensions → Bouclier')
    : iosVersion && iosVersion < 18
      ? tr('platform_settings_ios_16', 'Settings → Safari → Extensions → Bouclier')
      : tr('platform_settings_ios_18', 'Settings → Apps → Safari → Extensions → Bouclier');
  globalThis.BOUCLIER_PLATFORM = Object.freeze({
    kind,
    ios,
    device: tr(`platform_device_${kind}`, kind === 'iphone' ? 'this iPhone' : kind === 'ipad' ? 'this iPad' : 'this Mac'),
    iosVersion,
    extensionSettings,
    // how to allow Bouclier on every website by hand, when Safari does not ask (the button names are Safari's own)
    accessSteps: ios
      ? tr('platform_steps_ios', `In ${extensionSettings}, set All Websites to Allow.`, extensionSettings)
      : tr('platform_steps_mac', `In ${extensionSettings}, click “Always Allow on Every Website…” (or Edit Websites…, then Allow for other websites).`, extensionSettings),
  });
})();
