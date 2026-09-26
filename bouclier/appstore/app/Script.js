// Bouclier's app window. The Swift view controller from Apple's Safari extension template calls
// show(platform, enabled, useSettingsInsteadOfPreferences) and listens for "open-preferences".
function show(platform, enabled, useSettingsInsteadOfPreferences) {
  document.body.classList.remove('platform-mac', 'platform-ios');
  document.body.classList.add(`platform-${platform}`);
  if (typeof enabled === 'boolean') {
    document.body.classList.toggle('state-on', enabled);
    document.body.classList.toggle('state-off', !enabled);
  } else {
    document.body.classList.remove('state-on', 'state-off');
  }
  void useSettingsInsteadOfPreferences; // Bouclier needs macOS 26, which always says "Settings"
}

function openPreferences() {
  webkit.messageHandlers.controller.postMessage('open-preferences');
}

document.querySelector('button.open-preferences').addEventListener('click', openPreferences);

// Until the app calls show(), guess the platform so the page is never empty.
if (!/platform-(mac|ios)/.test(document.body.className)) {
  const ios = /iPhone|iPad|iPod/.test(navigator.userAgent) || navigator.maxTouchPoints > 1;
  document.body.classList.add(ios ? 'platform-ios' : 'platform-mac');
}
