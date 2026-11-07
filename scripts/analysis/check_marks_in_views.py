import io, sys
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

with open('frontend/src/views/MerchantApply.vue', 'r', encoding='utf-8') as f:
    for i, line in enumerate(f, 1):
        if '✓' in line or '✗' in line:
            print(f"MerchantApply.vue:L{i}: {line.strip()[:80]}")
