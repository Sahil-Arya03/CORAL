import { chromium } from 'playwright';

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });

await page.goto('http://localhost:5173/#chat', { waitUntil: 'networkidle' });
await page.waitForSelector('textarea');

const prompt = 'What should I focus on today, and is anything due soon?';
await page.fill('textarea', prompt);
await page.getByRole('button', { name: 'Send', exact: true }).click();

// Mid-stream snapshot (thinking trace / first tokens)
await page.waitForTimeout(1200);
await page.screenshot({ path: 'shot-chat-thinking.png' });

// Wait for the streamed assistant turn to finish (>=2 assistant bubbles, caret gone)
await page.waitForFunction(() => {
  const b = [...document.querySelectorAll('.bubble.assistant')];
  if (b.length < 2) return false;
  const last = b[b.length - 1];
  return last.innerText.trim().length > 40 && !last.querySelector('.caret');
}, { timeout: 30000 });

await page.waitForTimeout(400);
await page.screenshot({ path: 'shot-chat-done.png' });

const answer = await page.evaluate(() => {
  const b = [...document.querySelectorAll('.bubble.assistant')];
  return b[b.length - 1].innerText.trim();
});
console.log('=== ASSISTANT ANSWER ===');
console.log(answer);

await browser.close();
