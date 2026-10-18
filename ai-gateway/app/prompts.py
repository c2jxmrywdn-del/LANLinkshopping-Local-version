# -*- coding: utf-8 -*-
"""电商场景专属 Prompt 模板。

按咨询意图分场景路由模板：商品信息 / 订单状态 / 物流查询 / 售后政策 / 支付 / 会员 / 通用。
所有模板强制模型「只依据知识库内容回答、不得编造」，并输出来源引用。
"""
from jieba import cut

SYSTEM_PROMPT = """你是 LANLink Shopping B2B 电商平台的智能客服助手，服务采购商与企业客户。
回答规则（必须严格遵守）：
1. 只能依据下方【知识库内容】回答，不得编造知识库之外的商品、价格、政策信息；
2. 知识库没有覆盖的问题，明确告知"该问题超出知识库范围，可转人工客服（工作日 9:00-18:00）"；
3. 回答要简洁准确，多用数字与条目；涉及金额/时效/数量时必须与知识库数据一致；
4. 回答末尾标注引用来源，格式：来源：《文档标题》；
5. 使用与用户提问相同的语言回答（中文提问用中文）。
【知识库内容】
{context}
——【知识库内容结束】——"""

SCENE_KEYWORDS = {
    "product": ["商品", "产品", "价格", "多少钱", "报价", "库存", "现货", "规格", "型号", "品牌", "参数", "有什么", "推荐", "分类", "类目"],
    "order": ["订单", "状态", "下单", "付款", "支付", "取消", "待发货", "待收货", "已完成", "积分", "抵扣", "开票", "发票"],
    "logistics": ["物流", "快递", "发货", "配送", "运费", "包邮", "到货", "签收", "轨迹", "单号", "运输"],
    "aftersales": ["售后", "退货", "退款", "换货", "质量", "坏了", "破损", "七天", "无理由", "维修"],
    "account": ["企业认证", "账期", "对公", "账号", "子账号", "会员", "等级", "权益"],
}

SCENE_TEMPLATES = {
    "product": "场景：商品咨询（信息/价格/库存/规格）。请根据知识库列出商品名称、品牌、规格、单价、库存等要素，若涉及多个商品可分条列出。",
    "order": "场景：订单与支付咨询（订单状态/支付方式/积分抵扣/开票）。请按知识库的订单状态流转与支付规则逐步说明，涉及积分抵扣时说明 1000 积分=1 元、最小抵扣 1 元、单笔上限 10%。",
    "logistics": "场景：物流配送咨询（发货时效/运费/轨迹查询/签收）。请说明发货时效、快递合作方、运费规则与查询方式。",
    "aftersales": "场景：售后咨询（退货/退款/换货/质量）。请说明适用条件、时间窗口、运费承担方与退款时效。",
    "account": "场景：企业账户咨询（认证/账期/权限/会员等级）。请按知识库说明流程、时效与权益。",
    "general": "场景：通用电商咨询。请直接依据知识库简洁作答。",
}


def detect_scene(question: str) -> str:
    """基于关键词命中的场景意图路由（可与大模型分类互为兜底）。"""
    scores = {}
    for scene, words in SCENE_KEYWORDS.items():
        scores[scene] = sum(1 for w in words if w in question)
    best = max(scores, key=scores.get)
    return best if scores[best] > 0 else "general"


def build_messages(question: str, context_docs: list, scene: str = None) -> list:
    """组装 OpenAI 兼容的 messages：system（模板+知识库）+ user（场景提示+问题）。"""
    scene = scene or detect_scene(question)
    context = "\n\n".join(
        f"《{d['title']}》（分类：{d['category']}）：{d['content']}" for d in context_docs
    ) or "（未检索到相关知识库内容）"
    system = SYSTEM_PROMPT.format(context=context)
    user = f"{SCENE_TEMPLATES.get(scene, SCENE_TEMPLATES['general'])}\n用户问题：{question}"
    return [
        {"role": "system", "content": system},
        {"role": "user", "content": user},
    ], scene
