// Bouclier's app window. The Swift view controller from Apple's Safari extension template calls
// show(platform, enabled, useSettingsInsteadOfPreferences) and listens for "open-preferences";
// tools/xcode_setup.py adds a 4th argument on iOS 26.2+: the button can open Bouclier in Settings.
//
// Language: English, or French when the app runs in French (the device is set to French; the app
// declares English and French, see tools/xcode_setup.py). The English texts are in Main.html.
/* exported show */

const FRENCH = {
  tagline: 'Bloqueur de pub pour Safari',
  mac_on: 'Bouclier est activé dans Safari.',
  mac_off: 'Bouclier est désactivé. Activez-le dans les réglages de Safari.',
  mac_unknown: 'Activez Bouclier dans les réglages de Safari pour commencer à bloquer.',
  ios_status: 'Activez Bouclier dans Réglages pour commencer à bloquer.',
  mac_step1: 'Ouvrez <strong>Safari → Réglages → Extensions</strong>.',
  mac_step2: 'Cochez <strong>Bouclier</strong>.',
  mac_step3: 'Cliquez sur <strong>Toujours autoriser sur chaque site web…</strong> (ou <strong>Modifier les sites web…</strong>, puis <strong>Autoriser</strong> pour les autres sites web). Bouclier masque alors aussi les espaces publicitaires vides, ferme les pop-ups publicitaires et retire les publicités vidéo.',
  mac_button: 'Quitter et ouvrir les réglages de Safari…',
  ios_step1: 'Ouvrez <strong class="ios-settings-path">Réglages → Apps → Safari → Extensions</strong>.',
  ios_step2: 'Activez <strong>Bouclier</strong>, puis réglez <strong>Tous les sites web</strong> sur <strong>Autoriser</strong> : Bouclier peut alors aussi masquer les espaces publicitaires vides, fermer les pop-ups publicitaires et retirer les publicités vidéo.',
  ios_step3: 'Dans Safari, ouvrez le menu de la page dans la barre d’adresse et touchez <strong>Bouclier</strong> pour voir ce qui a été bloqué, mettre un site en pause ou masquer un élément.',
  ios_button: 'Ouvrir Bouclier dans Réglages',
  privacy: 'Bouclier n’a pas de compte et ne collecte aucune donnée : les statistiques restent sur cet appareil.',
  test_link: 'Tester Bouclier dans Safari',
  test_desc: 'Une page de test indépendante charge de vraies adresses de pubs et de traqueurs et montre ce qui est passé.',
  credits: 'Listes de filtres : EasyList, EasyPrivacy, Liste FR, Adblock Warning Removal List, EasyList Social et Annoyances, par les auteurs d’EasyList (CC BY-SA 3.0), EasyList Cookie List (CC BY 3.0) et Online Malicious URL Blocklist de malware-filter, avec les données URLhaus d’abuse.ch (CC0). Les règles de Bouclier en sont adaptées et partagées sous les mêmes licences. Les crédits complets sont dans les réglages de Bouclier.',
};

const french = /^fr\b/i.test(navigator.language || '');

function translate() {
  if (!french) return;
  document.documentElement.lang = 'fr';
  for (const el of document.querySelectorAll('[data-i18n]')) {
    const s = FRENCH[el.dataset.i18n];
    if (s) el.textContent = s;
  }
  for (const el of document.querySelectorAll('[data-i18n-html]')) {
    const s = FRENCH[el.dataset.i18nHtml];
    if (s) el.innerHTML = s;   // Bouclier's own markup, never anything from outside
  }
}

function show(platform, enabled, useSettingsInsteadOfPreferences, canOpenSettings) {
  document.body.classList.remove('platform-mac', 'platform-ios');
  document.body.classList.add(`platform-${platform}`);
  if (typeof enabled === 'boolean') {
    document.body.classList.toggle('state-on', enabled);
    document.body.classList.toggle('state-off', !enabled);
  } else {
    document.body.classList.remove('state-on', 'state-off');
  }
  document.body.classList.toggle('can-open-settings', canOpenSettings === true);
  // macOS 12 still calls Safari's settings "Preferences" (the template tells us which word to use)
  if (platform === 'mac' && useSettingsInsteadOfPreferences === false) usePreferencesWording();
}

function usePreferencesWording() {
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  for (let node = walker.nextNode(); node; node = walker.nextNode()) {
    if (!node.parentElement.closest('.platform-mac')) continue;
    node.nodeValue = french
      ? node.nodeValue.replace(/Réglages/g, 'Préférences').replace(/réglages/g, 'préférences')
      : node.nodeValue.replace(/Settings/g, 'Preferences').replace(/settings/g, 'preferences');
  }
}

// iOS 18 moved Safari under Settings → Apps; iOS 16 and 17 list it directly in Settings.
function iosSettingsPath() {
  const m = navigator.userAgent.match(/(?:iPhone|CPU) OS (\d+)[_.]/);
  const version = m ? Number(m[1]) : null;
  const settings = french ? 'Réglages' : 'Settings';
  const path = version && version < 18 ? `${settings} → Safari → Extensions` : `${settings} → Apps → Safari → Extensions`;
  for (const el of document.querySelectorAll('.ios-settings-path')) el.textContent = path;
}

function openPreferences() {
  try {
    webkit.messageHandlers.controller.postMessage('open-preferences');
  } catch {
    // outside the app (a preview in a browser): nothing to open
  }
}

translate();
for (const button of document.querySelectorAll('button.open-preferences')) {
  button.addEventListener('click', openPreferences);
}
iosSettingsPath();

// Until the app calls show(), guess the platform so the page is never empty.
if (!/platform-(mac|ios)/.test(document.body.className)) {
  const ios = /iPhone|iPad|iPod/.test(navigator.userAgent) || navigator.maxTouchPoints > 1;
  document.body.classList.add(ios ? 'platform-ios' : 'platform-mac');
}
