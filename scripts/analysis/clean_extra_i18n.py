import io, sys
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

path = 'frontend/src/i18n/zh-en-extra.js'
with open(path, 'r', encoding='utf-8') as f:
    content = f.read()

# Exact removals
removals = [
    "  '💰 我的钱包': '💰 My Wallet',\r\n",
    "  '🎯 活动中心': '🎯 Activity Center',\r\n",
    "  '🛒 购物车': '🛒 Cart',\r\n",
    "  '🔔 消息': '🔔 Messages',\r\n",
    "  '🔥 促销进行中': '🔥 Promotion live',\r\n",
]

for r in removals:
    if r in content:
        content = content.replace(r, '')
        print("Removed line with emoji")
    else:
        # try LF
        r_lf = r.replace('\r\n', '\n')
        if r_lf in content:
            content = content.replace(r_lf, '')
            print("Removed line with emoji (LF)")

replacements = [
    (
        "'🔒 交易过程受平台安全机制保护': '🔒 Transactions are protected by platform security',",
        "'交易过程受平台安全机制保护': 'Transactions are protected by platform security',"
    ),
    (
        '"🧾 完税记录月度上报": "🧾 Monthly tax record submission",',
        '"完税记录月度上报": "Monthly tax record submission",'
    ),
    (
        '"🛡️ 加密商户档案": "🛡️ Encrypted Merchant Profile",',
        '"加密商户档案": "Encrypted Merchant Profile",'
    ),
    (
        '"✨ AI 建议回答与关键词": "✨ AI-suggested answer and keywords",',
        '"AI 建议回答与关键词": "AI-suggested answer and keywords",'
    ),
    (
        '"⚠️ 您勾选了有稳定纳税记录：本季度需上传近 3 个月共 3 份企业所得税完税凭证（还差 3 份）": "⚠️ You declared stable tax records: upload 3 corporate income tax payment certificates covering the last 3 months this quarter (3 still missing)",',
        '"您勾选了有稳定纳税记录：本季度需上传近 3 个月共 3 份企业所得税完税凭证（还差 3 份）": "You declared stable tax records: upload 3 corporate income tax payment certificates covering the last 3 months this quarter (3 still missing)",'
    ),
]

for old, new in replacements:
    if old in content:
        content = content.replace(old, new)
        print("Replaced emoji key/value pair")
    else:
        print("Pattern not found in content (already replaced or different formatting)")

with open(path, 'w', encoding='utf-8', newline='') as f:
    f.write(content)

print("Saved zh-en-extra.js successfully.")
