// Renders the overlay cards and the longest caption on a blank page (no app needed) for a visual check.
import { chromium } from 'playwright';
import fs from 'node:fs';
import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { installOverlay, preloadFonts, introCard, chapterCard, platformCard, outroCard } from './lib.mjs';
import { SCENES, LANG } from './narration.mjs';
const ff = createRequire(import.meta.url)('ffmpeg-static');

fs.mkdirSync('preview', { recursive: true });
const browser = await chromium.launch({ channel: 'chrome', headless: true });
const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
await page.setContent(`<html><head><title>x</title></head><body style="margin:0;background:#eef2f8;font-family:Segoe UI"><div id="root" style="padding:60px"><h1>Dashboard (page behind the overlay)</h1><p>Some app content to sit behind the caption.</p></div></body></html>`);
await installOverlay(page);
await preloadFonts(page);
const shot = async (name) => {
  const p = `preview/${LANG}_${name}.png`;
  await page.screenshot({ path: p });
  spawnSync(ff, ['-y', '-i', p, '-vf', 'scale=1280:-1', p.replace('.png', '_s.png')], { encoding: 'utf8' });
};
const show = async (html) => { await page.evaluate((h) => window.__nxCard(h), html); await page.waitForTimeout(2600); };
await show(introCard()); await shot('intro');
await show(chapterCard(1)); await shot('ch1');
await show(chapterCard(3)); await shot('ch3');
await show(platformCard()); await shot('platform');
await show(outroCard()); await shot('outro');
await page.evaluate(() => window.__nxCard(null));
const longest = SCENES.filter(s => s.caption).sort((a, b) => b.caption.length - a.caption.length)[0];
await page.evaluate((h) => window.__nxCaption(h), longest.caption);
await page.waitForTimeout(900);
await shot('caption');
console.log('longest caption:', longest.id, longest.caption.length, 'chars');
console.log('caption box:', await page.evaluate(() => { const r = document.getElementById('nx-caption').getBoundingClientRect(); return `${Math.round(r.width)}x${Math.round(r.height)} @ ${Math.round(r.left)},${Math.round(r.top)}`; }));
console.log('font loaded:', await page.evaluate(() => document.fonts.check('700 24px Cairo')));
await browser.close();
