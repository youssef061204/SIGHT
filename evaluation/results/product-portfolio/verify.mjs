import { chromium } from 'file:///C:/Users/joeso/Projects/ATLAS/frontend/node_modules/playwright/index.mjs';
import { mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';

const baseUrl = process.argv[2] ?? 'http://127.0.0.1:3107';
const scope = process.argv[3] ?? 'local';
assert.match(scope, /^[a-z-]+$/);
const output = new URL(`file:///C:/Users/joeso/Projects/SIGHT/.tools/portfolio-verification/${scope}/`);
await mkdir(output, { recursive: true });
const browser = await chromium.launch({ headless: true });
const results = [];
try {
  for (const viewport of [{ width: 1440, height: 1000 }, { width: 390, height: 844 }]) {
    const label = viewport.width === 390 ? 'mobile' : 'desktop';
    const context = await browser.newContext({ viewport, reducedMotion: 'reduce' });
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
    const response = await page.goto(baseUrl, { waitUntil: 'networkidle' });
    assert.equal(response.status(), 200);
    assert.equal(await page.locator('article.project-row').count(), 6);
    const card = page.locator('article.project-row').filter({ has: page.getByRole('heading', { name: /^SIGHT/ }) });
    assert.equal(await card.count(), 1);
    await card.scrollIntoViewIfNeeded();
    for (const img of await card.locator('img').all()) await img.evaluate(el => el.decode());
    assert.equal(await card.evaluate(el => el.scrollWidth <= el.clientWidth), true);
    await card.screenshot({ path: fileURLToPath(new URL(`${label}-featured.png`, output)) });
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
    await card.getByRole('link', { name: /Read case study/ }).click();
    await page.waitForURL('**/projects/sight');
    await page.waitForLoadState('networkidle');
    assert.match(await page.title(), /^SIGHT/);
    assert.equal(await page.locator('.phone-preview__screen').count(), 2);
    const repository = page.getByRole('link', { name: /View repository/ });
    assert.equal(await repository.getAttribute('href'), 'https://github.com/youssef061204/SIGHT');
    assert.match(await page.locator('.scope-note').innerText(), /not a certified mobility aid/);
    assert.match(await page.locator('main').innerText(), /automatic road narration is disabled|automatic road narration remains off|automatic road narration disabled/i);
    await page.locator('.case-hero').screenshot({ path: fileURLToPath(new URL(`${label}-case-hero.png`, output)) });
    const images = page.locator('.gallery-grid--phone img');
    assert.equal(await images.count(), 5);
    for (const img of await images.all()) {
      await img.scrollIntoViewIfNeeded();
      await img.evaluate(el => el.decode());
      assert.equal(await img.evaluate(el => el.naturalWidth > 0 && Math.abs(el.naturalWidth / el.naturalHeight - 0.45) < 0.01), true);
    }
    const video = page.getByLabel('SIGHT product demonstration (silent emulator walkthrough)');
    assert.equal(await video.count(), 1);
    assert.match(await page.locator('.gallery-item--video figcaption').innerText(), /^Silent walkthrough/);
    await video.scrollIntoViewIfNeeded();
    await video.evaluate(el => { el.muted = true; el.load(); });
    await page.waitForFunction(() => document.querySelector('video')?.readyState >= 2);
    const metadata = await video.evaluate(el => ({ duration: el.duration, width: el.videoWidth, height: el.videoHeight }));
    assert.ok(metadata.duration > 110 && metadata.duration < 116);
    assert.equal(metadata.width, 1280);
    assert.equal(metadata.height, 900);
    await video.evaluate(el => el.play());
    await page.waitForFunction(() => document.querySelector('video').currentTime > 0.2);
    await video.evaluate(el => el.pause());
    await page.screenshot({ path: fileURLToPath(new URL(`${label}-case-study.png`, output)), fullPage: true });
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
    assert.deepEqual(errors, []);
    results.push({ viewport, homepageStatus: response.status(), caseStudy: page.url(), galleryImages: await images.count(), repositoryHref: await repository.getAttribute('href'), video: metadata, consoleErrors: errors, horizontalOverflow: false });
    await context.close();
  }
} finally { await browser.close(); }
await writeFile(new URL('summary.json', output), JSON.stringify({ scope, baseUrl, verifiedAtUtc: new Date().toISOString(), results }, null, 2) + '\n');
console.log(JSON.stringify(results, null, 2));
