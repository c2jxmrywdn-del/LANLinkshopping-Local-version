"""训练工程配置（P1 静态接入用）。

默认从仓库根 `.env` 读取数据库密码/网关 Key，全部可被环境变量覆盖。
本目录只在本地/Linux(WSL2) 运行，不参与后端与容器构建。
"""
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TRAIN_DIR = ROOT / "training"
DATA_DIR = Path(os.getenv("TRAIN_DATA_DIR", TRAIN_DIR / "data"))
REPORT_DIR = Path(os.getenv("TRAIN_REPORT_DIR", TRAIN_DIR / "reports"))
TRACE_DIR = Path(os.getenv("AI_TRACE_DIR", ROOT / "ai-gateway" / "data" / "traces"))


def _dotenv(path: Path) -> dict:
    env = {}
    try:
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            env[k.strip()] = v.strip().strip('"').strip("'")
    except Exception:
        pass
    return env


ENV = _dotenv(ROOT / ".env")

DB = {
    "host": os.getenv("TRAIN_DB_HOST", "127.0.0.1"),
    "port": int(os.getenv("TRAIN_DB_PORT", "3306")),
    "user": os.getenv("TRAIN_DB_USER", "root"),
    "password": os.getenv("MYSQL_ROOT_PASSWORD") or ENV.get("MYSQL_ROOT_PASSWORD", ""),
    "database": os.getenv("MYSQL_DATABASE") or ENV.get("MYSQL_DATABASE", "lanlink_shopping"),
}

GATEWAY_BASE = os.getenv("TRAIN_GATEWAY_BASE", "http://127.0.0.1:8090/v1")
GATEWAY_KEY = (os.getenv("AI_GATEWAY_API_KEY") or ENV.get("AI_GATEWAY_API_KEY")
               or ENV.get("GATEWAY_API_KEY") or "")
INTENT_MODEL = os.getenv("TRAIN_INTENT_MODEL", "qwen2.5-7b")
REQUEST_TIMEOUT = int(os.getenv("TRAIN_REQUEST_TIMEOUT", "120"))

INTENTS = [
    ("account_access", "账号注册与登录"),
    ("merchant_onboarding", "商户入驻"),
    ("product_browse", "商品浏览"),
    ("cart_order", "购物车与订单"),
    ("wallet_credit", "钱包与账期"),
    ("membership_activity", "会员与活动"),
    ("message_privacy", "消息与隐私"),
    ("logistics_tracking", "物流与配送"),
    ("refund_aftersale", "退款与售后"),
    ("payment_invoice", "支付与发票"),
    ("complaint_human", "人工客服与投诉"),
]
INTENT_CODES = [c for c, _ in INTENTS]

# 知识库条目标识 → 意图（用于给 23 条回归用例打银标）
ENTRY_TO_INTENT = {
    "account-registration": "account_access",
    "account-login": "account_access",
    "product-browsing": "product_browse",
    "cart-checkout": "cart_order",
    "order-navigation": "cart_order",
    "merchant-onboarding": "merchant_onboarding",
    "wallet": "wallet_credit",
    "credit-terms": "wallet_credit",
    "membership": "membership_activity",
    "activities-promotions": "membership_activity",
    "messages": "message_privacy",
    "cookie-privacy": "message_privacy",
    "account-addresses": "message_privacy",
}

REG_CASES = [
    ROOT / "backend" / "src" / "test" / "resources" / "support-learning-sandbox-cases.json",
    ROOT / "support-learning-sandbox" / "conversation_regression_cases.json",
]
