"""构建意图数据集：种子 → 模板改写增广 → 按 seed 分组切分 → 去重/防泄漏 → 冻结评测集。

产出：
  data/splits/train_v1.jsonl / dev_v1.jsonl / eval_v1.jsonl
  data/splits/eval_v1.sha256 / manifest_v1.json
用法：python build_dataset.py
"""
import hashlib
import json
import re
import unicodedata
from pathlib import Path

import config
from config import INTENT_CODES

# 每类补 2 条种子（保证 8 seeds/类：6 CORPUS + 2 core）
CORES = {
    "account_access": ["注册时提示手机号已被占用怎么办", "登录后如何开启两步验证"],
    "merchant_onboarding": ["入驻商户需要准备哪些资料", "店铺审核被驳回怎么重新提交"],
    "product_browse": ["怎么按行业筛选商品目录", "商品详情页在哪里看规格"],
    "cart_order": ["购物车商品如何合并结算", "下单后在哪里查订单进度"],
    "wallet_credit": ["钱包余额如何充值", "企业账期还款怎么操作"],
    "membership_activity": ["会员等级怎么升级", "积分怎么兑换优惠券"],
    "message_privacy": ["站内消息怎么全部标为已读", "Cookie 设置在哪里修改"],
    "logistics_tracking": ["物流信息一直不更新怎么办", "怎么查快递单号和轨迹"],
    "refund_aftersale": ["申请退款后多久能到账", "售后换货流程怎么走"],
    "payment_invoice": ["支持哪些支付方式付款", "发票怎么申请开具"],
    "complaint_human": ["我要投诉客服服务态度", "怎么转接人工客服"],
}

# 每类 3 条中英混合问法（1 条进 eval，2 条进 train）
BILINGUAL = {
    "account_access": ["How do I register an account?", "2FA 验证码收不到怎么办", "忘记 password 怎么 reset"],
    "merchant_onboarding": ["How to become a supplier?", "入驻 merchant 的保证金是多少", "KYC 审核需要多久"],
    "product_browse": ["How to search products by category?", "SKU 规格在哪里查看", "产品 catalog 怎么浏览"],
    "cart_order": ["How to check my order status?", "购物车 checkout 怎么操作", "订单号在哪里看"],
    "wallet_credit": ["How to top up my wallet?", "credit line 额度怎么申请", "账单 repayment 截止日期"],
    "membership_activity": ["How to upgrade membership level?", "积分 points 怎么用", "会员 discount 是多少"],
    "message_privacy": ["How to manage cookies?", "How to turn off notification messages?", "隐私 privacy 设置在哪里"],
    "logistics_tracking": ["How to track my shipment?", "快递 courier 联系方式", "发货 delivery 要多久"],
    "refund_aftersale": ["How to apply for a refund?", "退货 return 运费谁承担", "after-sales 售后电话"],
    "payment_invoice": ["How do I get an invoice?", "payment 支付失败怎么办", "增值税 VAT 发票怎么开"],
    "complaint_human": ["How to contact human support?", "我想 complaint 投诉", "人工客服 service hours 是几点"],
}

MASKS = [
    "{q}", "请问{q}", "想问一下{q}", "麻烦问下{q}", "{q}呢", "{q}？",
    "咨询一下：{q}", "{q}，谢谢", "你好，{q}", "{q}可以怎么处理", "我想了解{q}", "{q}（急）",
]

TRAIN_PER_SEED, DEV_PER_SEED, EVAL_PER_SEED = 9, 12, 8


def norm_text(s: str) -> str:
    s = unicodedata.normalize("NFKC", s or "").lower()
    return re.sub(r"[\s\W_]+", "", s)


def bigrams(s):
    s = norm_text(s)
    return {s[i:i + 2] for i in range(len(s) - 1)} if len(s) > 1 else {s}


def jaccard(a, b):
    A, B = bigrams(a), bigrams(b)
    return len(A & B) / len(A | B) if (A | B) else 0.0


def strip_tail(q: str) -> str:
    return re.sub(r"[？?！!。~～\s]+$", "", q.strip())


def variants(seed_text: str, n: int):
    core = strip_tail(seed_text)
    out, seen = [], set()
    for m in MASKS:
        v = m.format(q=core) if "{q}" in m else core + m
        v = v.strip()
        if not v.endswith(("？", "?")):
            v += "？"
        k = norm_text(v)
        if k and k not in seen:
            seen.add(k); out.append(v)
        if len(out) >= n:
            break
    return out


def load_seeds():
    path = config.DATA_DIR / "seeds" / "seeds_v1.jsonl"
    rows = [json.loads(l) for l in path.read_text(encoding="utf-8").splitlines() if l.strip()]
    by_cls = {c: [] for c in INTENT_CODES}
    for r in rows:
        if r["intent"] in by_cls:
            by_cls[r["intent"]].append(r)
    # 补齐 core 与双语种子
    for cls in INTENT_CODES:
        existing = {norm_text(r["text"]) for r in by_cls[cls]}
        for i, q in enumerate(CORES.get(cls, []), 1):
            if norm_text(q) not in existing:
                by_cls[cls].append({"seed_id": f"core-{cls}-{i}", "intent": cls, "text": q,
                                    "source": "core", "lang": "zh"})
                existing.add(norm_text(q))
        for i, q in enumerate(BILINGUAL.get(cls, []), 1):
            if norm_text(q) not in existing:
                by_cls[cls].append({"seed_id": f"bi-{cls}-{i}", "intent": cls, "text": q,
                                    "source": "bilingual",
                                    "lang": "en" if not re.search(r"[\u4e00-\u9fff]", q) else "mixed"})
                existing.add(norm_text(q))
    return by_cls


def main():
    by_cls = load_seeds()
    train, dev, evalset = [], [], []

    for cls in INTENT_CODES:
        seeds = sorted(by_cls[cls], key=lambda r: r["seed_id"])
        base = [s for s in seeds if not s["seed_id"].startswith("bi-")]
        bi = [s for s in seeds if s["seed_id"].startswith("bi-")]
        train_seeds = base[:5] + bi[1:3]
        dev_seeds = base[5:6]
        eval_seeds = base[6:8] + bi[:1]

        def emit(seed_list, n, bucket):
            for s in seed_list:
                for v in variants(s["text"], n):
                    bucket.append({
                        "question": v, "intent": cls, "seed_id": s["seed_id"],
                        "label_source": s["source"], "lang": s["lang"],
                    })

        emit(train_seeds, TRAIN_PER_SEED, train)
        emit(dev_seeds, DEV_PER_SEED, dev)
        emit(eval_seeds, EVAL_PER_SEED, evalset)

    # 去重（精确）+ 防泄漏（train/dev 与 eval 近重复剔除）
    def dedup(rows):
        seen, out = set(), []
        for r in rows:
            k = norm_text(r["question"])
            if k and k not in seen:
                seen.add(k); out.append(r)
        return out

    evalset = dedup(evalset)
    eval_keys = [r["question"] for r in evalset]
    def leak_free(rows):
        out = dedup(rows)
        kept = []
        for r in out:
            if any(jaccard(r["question"], e) >= 0.85 for e in eval_keys):
                continue
            kept.append(r)
        return kept

    train, dev = leak_free(train), leak_free(dev)

    out_dir = config.DATA_DIR / "splits"
    out_dir.mkdir(parents=True, exist_ok=True)

    def dump(name, rows):
        p = out_dir / f"{name}_v1.jsonl"
        with open(p, "w", encoding="utf-8") as fp:
            for r in rows:
                fp.write(json.dumps(r, ensure_ascii=False) + "\n")
        return p

    pt, pd, pe = dump("train", train), dump("dev", dev), dump("eval", evalset)
    sha = hashlib.sha256(peer_bytes(pe)).hexdigest()
    (out_dir / "eval_v1.sha256").write_text(sha + "\n", encoding="utf-8")

    stats = {}
    for cls in INTENT_CODES:
        stats[cls] = {
            "train": sum(1 for r in train if r["intent"] == cls),
            "dev": sum(1 for r in dev if r["intent"] == cls),
            "eval": sum(1 for r in evalset if r["intent"] == cls),
        }
    manifest = {
        "version": "v1", "eval_sha256": sha,
        "total": {"train": len(train), "dev": len(dev), "eval": len(evalset)},
        "per_class": stats,
        "bilingual": {
            "train": sum(1 for r in train if r["lang"] != "zh"),
            "eval": sum(1 for r in evalset if r["lang"] != "zh"),
        },
    }
    (out_dir / "manifest_v1.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


def peer_bytes(path: Path) -> bytes:
    return path.read_bytes() if path.exists() else b""


if __name__ == "__main__":
    main()
