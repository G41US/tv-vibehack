# Start-up animation

`anim.html` draws the TV Home start-up animation on a canvas; `render.js` saves every frame with Playwright.
To change it, edit `anim.html`, then:

```
node render.js 60
ffmpeg -framerate 60 -i frames/f%04d.png -c:v libx264 -profile:v main -level 4.2 -preset slow -crf 20 \
  -pix_fmt yuv420p -color_primaries bt709 -color_trc bt709 -colorspace bt709 -movflags +faststart \
  ../../launcher/app/src/main/res/raw/startup.mp4
```

`node render.js 30 1.0,2.5,4.0` saves stills at those seconds instead (s00.png, s01.png, …).
