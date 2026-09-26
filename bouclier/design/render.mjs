import sharp from '/home/claude/.npm-global/lib/node_modules/sharp/lib/index.js';
import fs from 'fs';
const out = '../extension/icons';
fs.mkdirSync(out, { recursive: true });
const app = fs.readFileSync('shield-app.svg');
const tb = fs.readFileSync('shield-toolbar.svg');
const tbOff = fs.readFileSync('shield-toolbar-off.svg');
for (const s of [48, 96, 128, 256, 512]) await sharp(app, { density: 400 }).resize(s, s).png().toFile(`${out}/icon-${s}.png`);
for (const s of [16, 19, 32, 38, 48]) {
  await sharp(tb, { density: 600 }).resize(s, s).png().toFile(`${out}/toolbar-${s}.png`);
  await sharp(tbOff, { density: 600 }).resize(s, s).png().toFile(`${out}/toolbar-off-${s}.png`);
}
fs.mkdirSync('appicon', { recursive: true });
for (const s of [16, 32, 64, 128, 256, 512, 1024]) await sharp(app, { density: 800 }).resize(s, s).png().toFile(`appicon/icon_${s}.png`);
// Full-bleed square icon (opaque, no rounded corners: iOS and macOS 26 round it themselves).
// Used for the App Store fallback icon set; the main app icon is ../appstore/AppIcon.icon (Icon Composer).
const full = fs.readFileSync('icon/full-bleed.svg');
fs.mkdirSync('appicon-fullbleed', { recursive: true });
for (const s of [16, 32, 64, 128, 256, 512, 1024]) {
  await sharp(full, { density: 800 }).resize(s, s).flatten({ background: '#1e3a8a' }).removeAlpha().png().toFile(`appicon-fullbleed/icon_${s}.png`);
}
console.log('ok');
