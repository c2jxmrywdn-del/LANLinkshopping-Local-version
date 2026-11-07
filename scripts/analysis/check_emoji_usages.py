import io
import sys
import os

targets = [
    '完税记录月度上报',
    '加密商户档案',
    'AI 建议回答与关键词',
    '您勾选了有稳定纳税记录',
    '交易过程受平台安全机制保护',
    '促销进行中'
]

src_dir = 'frontend/src'
for root, _, files in os.walk(src_dir):
    for f in files:
        if f.endswith(('.vue', '.js', '.ts')):
            path = os.path.join(root, f)
            with open(path, 'r', encoding='utf-8', errors='ignore') as fp:
                content = fp.read()
                for t in targets:
                    if t in content:
                        print(f'Found "{t}" in {path}')
