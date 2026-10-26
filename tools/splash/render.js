// node render.js <fps> [t1,t2,...]  -> frames/fNNNN.png, or stills sNN.png at the given times
const { chromium } = require('playwright');
const fs = require('fs');
(async () => {
  const fps = +process.argv[2] || 30, stills = process.argv[3];
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: 1920, height: 1080 } });
  p.on('console', m => console.log('page:', m.text()));
  p.on('pageerror', e => { console.error('ERR', e.message); process.exit(1); });
  await p.goto('file://' + __dirname + '/anim.html');
  await p.evaluate(() => document.fonts.load('500 104px Outfit'));
  const shot = async (t, path) => { await p.evaluate(t => draw(t), t); await p.screenshot({ path }); };
  if (stills) {
    let i = 0; for (const t of stills.split(',')) await shot(+t, __dirname + `/s${String(i++).padStart(2, '0')}.png`);
  } else {
    fs.mkdirSync(__dirname + '/frames', { recursive: true });
    const end = await p.evaluate(() => END), n = Math.round(end * fps);
    for (let i = 0; i <= n; i++) await shot(i / fps, __dirname + `/frames/f${String(i).padStart(4, '0')}.png`);
    console.log('frames', n + 1);
  }
  await b.close();
})();
