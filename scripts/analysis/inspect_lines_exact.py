import io, sys
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

with open('frontend/src/i18n/zh-en-extra.js', 'r', encoding='utf-8') as f:
    lines = f.readlines()

for i, l in enumerate(lines):
    if any(k in l for k in ['完税记录月度上报', '加密商户档案', 'AI 建议回答与关键词', '您勾选了有稳定纳税记录', '交易过程受平台安全机制保护', '促销进行中']):
        print(f"L{i+1}: {repr(l)}")
