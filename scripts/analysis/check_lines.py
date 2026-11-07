import re

for path in ['frontend/src/views/MerchantApply.vue', 'frontend/src/views/ProductDetail.vue', 'frontend/src/views/admin/CustomerCommunicationLearningReview.vue']:
    with open(path, 'r', encoding='utf-8') as f:
        for i, line in enumerate(f, 1):
            if any(k in line for k in ['完税记录月度上报', '加密商户档案', '交易过程受平台安全机制保护', 'AI 建议回答与关键词']):
                print(f"{path}:L{i}: {line.strip()[:80]}")
