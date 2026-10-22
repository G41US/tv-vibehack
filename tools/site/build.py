#!/usr/bin/env python3
"""Builds the install page in every language: docs/index.html (English) and docs/<code>/index.html.

Text lives in tools/site/lang/<code>.json (HTML allowed); the layout in template.html.
Run from anywhere: python3 tools/site/build.py
"""
import json
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
DOCS = os.path.join(HERE, '..', '..', 'docs')
SITE = 'https://g41us.github.io/tv-vibehack/'
# Order of the language links; English is the site root.
LANGS = ['en', 'fr', 'es', 'pt', 'de', 'ca', 'ar', 'hi', 'sw', 'id', 'th', 'zh']
RTL = {'ar'}

template = open(os.path.join(HERE, 'template.html'), encoding='utf-8').read()
texts = {c: json.load(open(os.path.join(HERE, 'lang', c + '.json'), encoding='utf-8')) for c in LANGS}
english_keys = set(texts['en'])


def path(code):
    return '' if code == 'en' else code + '/'


for code in LANGS:
    t = texts[code]
    missing = english_keys - set(t)
    assert not missing, f"{code}: missing {sorted(missing)}"
    root = '' if code == 'en' else '../'
    langs = ' '.join(f'<span>{texts[c]["name"]}</span>' if c == code
                     else f'<a href="{root}{path(c)}" hreflang="{c}" lang="{c}">{texts[c]["name"]}</a>'
                     for c in LANGS)
    alternates = '\n'.join(f'<link rel="alternate" hreflang="{c}" href="{SITE}{path(c)}">' for c in LANGS)
    values = dict(t)
    values.update(
        lang=code, dir='rtl' if code in RTL else 'ltr', root=root, langs=langs, alternates=alternates,
        features='\n'.join(f'    <li>{f}</li>' for f in t['features']),
        tips='\n'.join(f'  <div class="tip">{tip}</div>' for tip in t['tips']),
        questions='\n'.join(f'  <details><summary>{q}</summary>\n    <p>{a}</p></details>' for q, a in t['questions']),
    )
    page = re.sub(r'\{\{(\w+)\}\}', lambda m: str(values[m.group(1)]), template)
    out = os.path.join(DOCS, path(code), 'index.html')
    os.makedirs(os.path.dirname(out), exist_ok=True)
    open(out, 'w', encoding='utf-8').write(page)
    print('wrote', os.path.relpath(out, DOCS))
