/*
 * Bouclier – the extension's texts in English (the default) and French.
 * Safari chooses the language from the device's languages: French on an iPhone, iPad or Mac set to
 * French, English everywhere else. The texts live in _locales/<lang>/messages.json, which
 * tools/i18n.py makes from extension/i18n/*.json (one file per page, English and French side by side).
 *
 *   t('key', a, b)  the text, with {0}, {1}… replaced by a, b
 *   apply(root)     fills [data-i18n] (plain text), [data-i18n-html] (Bouclier's own markup, never
 *                   page content) and [data-i18n-attr="aria-label:key,title:key"]
 *   lang()          'fr' or 'en'
 *   locale()        for toLocaleString & co: 'fr' in French, undefined in English (the device's own
 *                   regional formats, as before the translation)
 */
(() => {
  'use strict';
  const api = globalThis.browser ?? globalThis.chrome;

  function t(key, ...subs) {
    let s = '';
    try { s = api.i18n.getMessage(key) || ''; } catch { /* no i18n here */ }
    if (!s) return key;   // a missing text shows its key, which the tests look for
    return subs.length ? s.replace(/\{(\d)\}/g, (m, i) => (subs[i] !== undefined ? String(subs[i]) : m)) : s;
  }

  function lang() {
    try { return /^fr\b/i.test(api.i18n.getUILanguage() || '') ? 'fr' : 'en'; } catch { return 'en'; }
  }

  function locale() {
    return lang() === 'fr' ? 'fr' : undefined;
  }

  function apply(root = document) {
    for (const el of root.querySelectorAll('[data-i18n]')) el.textContent = t(el.dataset.i18n);
    for (const el of root.querySelectorAll('[data-i18n-html]')) el.innerHTML = t(el.dataset.i18nHtml);
    for (const el of root.querySelectorAll('[data-i18n-attr]')) {
      for (const pair of el.dataset.i18nAttr.split(',')) {
        const [attr, key] = pair.split(':').map(x => x.trim());
        if (attr && key) el.setAttribute(attr, t(key));
      }
    }
    if (root === document) {
      document.documentElement.lang = lang();
      const title = document.querySelector('title[data-i18n]');
      if (title) document.title = title.textContent;
    }
  }

  globalThis.BOUCLIER_I18N = Object.freeze({ t, lang, locale, apply });
})();
