import io
import sys
import re

files = [
    'frontend/src/views/MerchantApply.vue',
    'frontend/src/views/ProductDetail.vue',
    'frontend/src/views/admin/CustomerCommunicationLearningReview.vue',
    'frontend/src/i18n/index.js',
    'frontend/src/i18n/zh-en-extra.js'
]

emoji_pattern = re.compile(r'[\U00010000-\U0010ffff\u2600-\u26ff\ufe0f]')

for path in files:
    print(f"=== {path} ===")
    with open(path, 'r', encoding='utf-8') as f:
        for lno, line in enumerate(f, 1):
            matches = emoji_pattern.findall(line)
            if matches:
                # print escaped unicode
                escaped = ''.join(f'\\u{ord(c):04x}' if ord(c) < 65536 else f'\\U{ord(c):08x}' for c in matches)
                sanitized = emoji_pattern.sub(lambda m: f'[{m.group(0).encode("unicode_escape").decode("utf-8")}]', line.strip())
                print(f"L{lno}: {escaped} -> {sanitized}")
