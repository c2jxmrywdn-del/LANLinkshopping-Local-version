import io, sys
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

with open('frontend/src/i18n/zh-en-extra.js', 'r', encoding='utf-8') as f:
    lines = f.readlines()

for lno in [901, 903, 1029, 1099, 1100, 1615, 1616, 1618, 1619]:
    print(f"L{lno}: {repr(lines[lno-1])}")
