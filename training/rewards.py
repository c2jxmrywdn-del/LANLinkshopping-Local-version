"""意图评测指标：准确率、macro-F1（有样本类 & 全 11 类两种口径）、每类 P/R/F1、混淆对、bootstrap 95% CI。"""


def _f1(tp, fp, fn):
    p = tp / (tp + fp) if tp + fp else 0.0
    r = tp / (tp + fn) if tp + fn else 0.0
    return (2 * p * r / (p + r) if p + r else 0.0), p, r


def _acc(y_true, y_pred):
    return sum(1 for t, p in zip(y_true, y_pred) if t == p) / len(y_true) if y_true else 0.0


def bootstrap_ci(y_true, y_pred, rounds=1000, seed=42):
    """准确率的 bootstrap 95% 置信区间（小样本必需的诚实区间）。"""
    import random
    rnd = random.Random(seed)
    n = len(y_true)
    if n == 0:
        return [0.0, 0.0]
    scores = []
    for _ in range(rounds):
        idx = [rnd.randrange(n) for _ in range(n)]
        scores.append(_acc([y_true[i] for i in idx], [y_pred[i] for i in idx]))
    scores.sort()
    return [round(scores[int(0.025 * rounds)], 4), round(scores[int(0.975 * rounds) - 1], 4)]


def evaluate(y_true, y_pred, labels):
    assert len(y_true) == len(y_pred)
    n = len(y_true)
    correct = sum(1 for t, p in zip(y_true, y_pred) if t == p)
    per = {}
    for lab in labels:
        tp = sum(1 for t, p in zip(y_true, y_pred) if t == lab and p == lab)
        fp = sum(1 for t, p in zip(y_true, y_pred) if t != lab and p == lab)
        fn = sum(1 for t, p in zip(y_true, y_pred) if t == lab and p != lab)
        f1, pr, rc = _f1(tp, fp, fn)
        support = sum(1 for t in y_true if t == lab)
        per[lab] = {"precision": round(pr, 4), "recall": round(rc, 4),
                    "f1": round(f1, 4), "support": support}

    supported = [lab for lab in labels if per[lab]["support"] > 0]
    macro_all = sum(v["f1"] for v in per.values()) / len(labels) if labels else 0.0
    macro_sup = sum(per[lab]["f1"] for lab in supported) / len(supported) if supported else 0.0

    confusions = {}
    for t, p in zip(y_true, y_pred):
        if t != p:
            confusions[f"{t}->{p}"] = confusions.get(f"{t}->{p}", 0) + 1
    top_conf = dict(sorted(confusions.items(), key=lambda kv: kv[1], reverse=True)[:12])
    return {
        "total": n,
        "accuracy": round(correct / n, 4) if n else 0.0,
        "accuracy_ci95": bootstrap_ci(y_true, y_pred),
        "macro_f1": round(macro_sup, 4),            # 主口径：有样本类别
        "macro_f1_all_labels": round(macro_all, 4),  # 兼容旧报告口径
        "supported_classes": supported,
        "per_class": per,
        "top_confusions": top_conf,
    }
