import re

pairs = [
    ('我的钱包', 'My Wallet'),
    ('活动中心', 'Activity Center'),
    ('购物车', 'Cart'),
    ('消息', 'Messages'),
    ('促销进行中', 'Promotion live'),
    ('交易过程受平台安全机制保护', 'Transactions are protected by platform security'),
    ('完税记录月度上报', 'Monthly tax record submission'),
    ('加密商户档案', 'Encrypted Merchant Profile'),
    ('AI 建议回答与关键词', 'AI-suggested answer and keywords'),
    ('您勾选了有稳定纳税记录：本季度需上传近 3 个月共 3 份企业所得税完税凭证（还差 3 份）', 'You declared stable tax records: upload 3 corporate income tax payment certificates covering the last 3 months this quarter (3 still missing)')
]

with open('frontend/src/i18n/zh-en-extra.js', 'r', encoding='utf-8') as f:
    extra_content = f.read()

with open('frontend/src/i18n/index.js', 'r', encoding='utf-8') as f:
    index_content = f.read()

for zh, en in pairs:
    in_extra = zh in extra_content
    in_index = zh in index_content
    print(f"'{zh}': in_extra={in_extra}, in_index={in_index}")
