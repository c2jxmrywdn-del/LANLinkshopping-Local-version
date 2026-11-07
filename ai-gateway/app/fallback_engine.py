# -*- coding: utf-8 -*-
"""网关启发式规则降级引擎（Heuristic Fallback Engine）。

设计定位：
当外部或本地大模型推理服务（llama.cpp / Ollama）触发超时（>5000ms）、挂死、连接拒绝、
或进入熔断器 OPEN 状态时，AI 网关自动切入本启发式规则引擎，保障业务 100% 可用、零 500 异常。

核心功能：
1. 意图与槽位识别：基于 jieba 分词与正则规则，精准提取业务意图与参数（商品选型、阶梯询价、物流追踪、政策问答）；
2. 确定性工具调用：直接编排调用 TOOLBOX 中的电商数据与风控工具（lanlink_search_products, lanlink_calculate_quotation, lanlink_track_logistics）；
3. 结构化离线问答：在无外部向量模型时，纯内存 TF-IDF 检索离线电商规章制度（ecommerce_kb.json），输出权威答案；
4. 应急服务标头与防御提示：统一附带离线应急服务警示与下一步操作指引。
"""
import json
import logging
import os
import re
from typing import Any, Dict, List, Optional, Tuple

import jieba

from .retriever import VectorIndex, default_kb_path
from . import tools as TOOLBOX

logger = logging.getLogger("lanlink.fallback-engine")

_FALLBACK_BANNER = (
    "> ⚠️ **【离线应急服务】大模型推理服务触发保护或不可达，已由网关启发式规则降级引擎接管保障业务连续性。**\n\n"
)

# 政策问答特征词（触发优先走离线知识库检索）
POLICY_TRIGGERS = {
    "政策", "规则", "规范", "质保", "保修", "退货", "退款", "售后", "发票",
    "条款", "流程", "资质", "入驻", "结算", "指南", "问答", "制度", "赔付"
}

# 搜索停用词（槽位抽取时过滤掉常见询问、语气词及商城通用词）
_SEARCH_STOP_WORDS = {
    "请", "请问", "帮我", "想要", "我想", "我想买", "需要", "有没有", "推荐", "搜索",
    "查找", "找一下", "找找", "查一下", "查询", "看一下", "咨询", "有哪些", "几款", "几件",
    "这个", "那个", "一下", "可以", "怎么", "买", "采购", "选型", "型号", "产品", "商品",
    "工业级", "企业级", "多少", "什么", "的", "了", "和", "及", "有", "要", "商城", "平台",
    "现货", "在售"
}


class HeuristicFallbackEngine:
    """网关启发式规则降级引擎"""

    def __init__(self, kb_path: Optional[str] = None):
        self.kb_path = kb_path or default_kb_path()
        self._offline_index: Optional[VectorIndex] = None
        self._load_offline_kb()

    def _load_offline_kb(self) -> None:
        """纯本地内存加载离线知识库索引（零网络与向量模型依赖）"""
        if os.path.isfile(self.kb_path):
            try:
                with open(self.kb_path, "r", encoding="utf-8") as f:
                    data = json.load(f)
                docs = data.get("documents", [])
                if docs:
                    self._offline_index = VectorIndex(docs)
                    logger.info("[fallback-engine] 离线知识库索引就绪，文档数: %d", len(docs))
            except Exception as e:
                logger.warning("[fallback-engine] 加载离线知识库失败: %s", e)

    # ----------------------- 意图与槽位抽取 -----------------------

    def extract_quotation_intent(self, text: str) -> Optional[Dict[str, int]]:
        """提取阶梯询价意图：商品/SKU ID + 采购数量。
        例："计算商品 1 买 500 件的价格", "商品2询价100个", "skuId: 1, 数量 200"
        """
        # 匹配商品 ID
        prod_m = re.search(r"(?:商品|产品|sku|skuid|id|编号)\s*[:：#]?\s*(\d+)", text, re.I)
        # 匹配数量（带数量单位或数量关键词）
        qty_m = re.search(r"(\d+)\s*(?:件|个|台|米|套|条|支|箱|批|吨|卷|根)", text)
        if not qty_m:
            qty_m = re.search(r"(?:数量|采购量|买|要|询价|计算)\s*[:：]?\s*(\d+)", text)

        if prod_m and qty_m:
            try:
                pid = int(prod_m.group(1))
                qty = int(qty_m.group(1))
                if pid > 0 and qty > 0:
                    return {"productId": pid, "quantity": qty}
            except (ValueError, TypeError):
                pass
        return None

    def extract_logistics_intent(self, text: str) -> Optional[Dict[str, Any]]:
        """提取物流查询意图：订单号或运单号。
        例："查询订单 20261022170813519896 的物流", "物流跟踪 LL202610230001"
        """
        # 1. 匹配标准平台订单号（20位左右数字）
        order_m = re.search(r"\b(20\d{16,22})\b", text)
        if not order_m:
            order_m = re.search(r"(?:订单|订单号|单号)\s*[:：#]?\s*([0-9A-Za-z_]{15,30})", text)

        # 2. 匹配运单号（LL开头或类似）
        waybill_m = re.search(r"\b(LL\d{10,20})\b", text, re.I)

        if order_m or waybill_m:
            order_no = order_m.group(1) if order_m else ""
            target_no = order_no or (waybill_m.group(1) if waybill_m else "")
            user_m = re.search(r"(?:user|userid|用户|用户id)\s*[:：]?\s*(\d+)", text, re.I)
            user_id = int(user_m.group(1)) if user_m else 4
            return {"orderNo": target_no, "userId": user_id}
        return None

    def extract_search_intent(self, text: str) -> Optional[Dict[str, Any]]:
        """提取商品选型与搜索意图。
        仅当包含明确选型/搜索触发词，或命中工业品类特征词时才判定为搜索意图；过滤停用词后提取商品核心词。
        """
        search_triggers = {
            "推荐", "搜索", "查", "找", "买", "想要", "选型", "采购", "有没有", "型号",
            "规格", "询价", "报价", "多少钱", "在售", "现货", "看看", "商品", "产品", "有哪些"
        }
        has_trigger = any(trig in text for trig in search_triggers)

        # 工业品与建材常见核心品类特征词
        mall_keywords = {
            "钢材", "螺纹钢", "盘条", "水泥", "硅酸盐", "管材", "给水管", "ppr", "坯布", "棉布",
            "短纤", "涤纶", "交换机", "路由器", "网线", "光模块", "电缆", "光纤", "服务器",
            "阀门", "轴承", "法兰", "紧固件", "仪器", "仪表", "配件", "五金", "建材", "工业品"
        }
        has_mall_category = any(cat in text.lower() for cat in mall_keywords)

        if not (has_trigger or has_mall_category):
            return None

        # 提取数量上限（例如 "5款", "3个"）
        limit_m = re.search(r"(\d+)\s*(?:款|种|个|项)", text)
        page_size = min(10, max(1, int(limit_m.group(1)))) if limit_m else 5

        # 使用 jieba 提取名词/关键词
        words = [w.strip() for w in jieba.cut(text) if w.strip()]
        kw_candidates = [
            w for w in words
            if w not in _SEARCH_STOP_WORDS and w not in POLICY_TRIGGERS and len(w) > 1 and not w.isdigit()
        ]

        if kw_candidates:
            # 中文分词连接不加空格，与后端 LIKE 匹配保持一致
            keyword = "".join(kw_candidates[:2])
            return {"keyword": keyword, "pageSize": page_size}

        return None

    # ----------------------- 业务场景渲染 -----------------------

    def handle_quotation(self, params: Dict[str, int]) -> Tuple[str, List[Dict[str, Any]]]:
        """处理阶梯询价降级业务"""
        res = TOOLBOX.execute_tool("lanlink_calculate_quotation", params)
        trace = [{"tool": "lanlink_calculate_quotation", "ok": res.get("ok"), "ms": res.get("elapsedMs")}]
        if not res.get("ok"):
            err_msg = res.get("error") or "询价核算失败"
            msg = (
                f"{_FALLBACK_BANNER}"
                f"抱歉，对商品 ID `{params.get('productId')}` 的核价请求未能成功：**{err_msg}**。\n\n"
                f"💡 建议核对商品 ID 是否存在，或输入类似：`计算商品 1 买 100 件的价格` 重试。"
            )
            return msg, trace

        data = res.get("data") or {}
        title = data.get("title", f"商品 #{params.get('productId')}")
        qty = data.get("quantity", params.get("quantity"))
        orig_price = data.get("originalUnitPrice", 0.0)
        eff_price = data.get("effectiveUnitPrice", 0.0)
        tier_desc = data.get("appliedTierDesc", "现货零售单价")
        goods_total = data.get("goodsTotalAmount", 0.0)
        freight = data.get("estimatedFreight", 0.0)
        vat_tax = data.get("estimatedVatTax", 0.0)
        final_total = data.get("finalTotalAmount", 0.0)
        savings = data.get("totalSavings", 0.0)
        summary = data.get("quotationSummary", "")

        msg = (
            f"{_FALLBACK_BANNER}"
            f"### 📋 【核价结果】{title}\n\n"
            f"- **采购数量**：`{qty}` 件\n"
            f"- **价格阶梯**：`{tier_desc}`\n"
            f"- **基准现货单价**：¥{orig_price:,.2f}\n"
            f"- **阶梯优惠单价**：**¥{eff_price:,.2f}**\n"
            f"- **货款小计**：¥{goods_total:,.2f}\n"
            f"- **预估物流运费**：¥{freight:,.2f}（{'已享包邮' if freight == 0 else '标准干线大件物流'}）\n"
            f"- **预估增值税额 (13%)**：¥{vat_tax:,.2f}\n"
            f"- **最终应付总金额**：**¥{final_total:,.2f}**"
        )
        if savings > 0:
            msg += f" *(累计优惠节省: ¥{savings:,.2f})*"
        msg += f"\n\n> 💡 **核算速记**：{summary}\n\n"
        msg += (
            f"---\n"
            f"👉 **下一步操作**：\n"
            f"- 如需生成草稿采购单，请回复：`为商品 {params.get('productId')} 生成采购草稿单，数量 {qty}`\n"
            f"- 如需查询其他规格，请回复：`搜索 {title[:4]}`"
        )
        return msg, trace

    def handle_logistics(self, params: Dict[str, Any]) -> Tuple[str, List[Dict[str, Any]]]:
        """处理物流追踪降级业务"""
        res = TOOLBOX.execute_tool("lanlink_track_logistics", params)
        trace = [{"tool": "lanlink_track_logistics", "ok": res.get("ok"), "ms": res.get("elapsedMs")}]
        if not res.get("ok"):
            err_msg = res.get("error") or "运单不存在或无权访问"
            msg = (
                f"{_FALLBACK_BANNER}"
                f"抱歉，查询订单 `{params.get('orderNo')}` 物流轨迹未成功：**{err_msg}**。\n\n"
                f"💡 请核对平台订单号是否正确（格式如：`20261022170813519896`）。"
            )
            return msg, trace

        data = res.get("data") or {}
        order_no = data.get("orderNo", params.get("orderNo"))
        carrier = data.get("carrierName", "专线冷链/大件快运")
        waybill = data.get("waybillNo") or data.get("expressNo") or "暂无单号"
        status_desc = data.get("orderStatusDesc", "运输中")
        receiver = data.get("receiverName", "客户")
        phone = data.get("receiverPhone", "")
        dest = data.get("destination", "客户指定收货地址")
        traces = data.get("traces") or []

        msg = (
            f"{_FALLBACK_BANNER}"
            f"### 🚚 【物流追踪】订单 {order_no}\n\n"
            f"- **承运商**：{carrier}\n"
            f"- **运单单号**：`{waybill}`\n"
            f"- **当前状态**：**{status_desc}**\n"
            f"- **收货信息**：{receiver} ({phone}) - {dest}\n\n"
            f"#### 📍 运输路由节点轨迹：\n"
        )
        if traces:
            for item in reversed(traces):
                time_str = item.get("time", "")
                loc = item.get("location", "")
                desc = item.get("description", "")
                loc_info = f"【{loc}】" if loc else ""
                msg += f"- `{time_str}` {loc_info}{desc}\n"
        else:
            msg += "- 暂未同步到详细路由分拨节点，包裹正处于揽收转运环节。\n"

        msg += "\n*注：LANLink 工业供应链 SLA 承诺全流程在途监控，异常延误将自动触发客诉理赔通道。*"
        return msg, trace

    def handle_search(self, params: Dict[str, Any]) -> Tuple[str, List[Dict[str, Any]]]:
        """处理商品选型与推荐降级业务"""
        res = TOOLBOX.execute_tool("lanlink_search_products", params)
        trace = [{"tool": "lanlink_search_products", "ok": res.get("ok"), "ms": res.get("elapsedMs")}]
        if not res.get("ok"):
            # 若连接后端服务失败或离线，优先回退离线知识库检索（离线 RAG 兜底）
            if self._offline_index:
                hits = self._offline_index.search(str(params.get("keyword") or ""), top_k=2, min_score=0.05)
                if hits:
                    best = hits[0]
                    title = best.get("title", "")
                    content = best.get("content", "")
                    return (
                        f"{_FALLBACK_BANNER}"
                        f"### 📦 【离线商品知识】《{title}》\n\n"
                        f"{content}\n\n"
                        f"---\n*注：实时在线库存微服务暂不可达，已自动切换至离线品类商品规范数据。*",
                        trace
                    )
            err_msg = res.get("error") or "检索未返回结果"
            return (
                f"{_FALLBACK_BANNER}"
                f"未查询到关键词 “{params.get('keyword')}” 相关的商品：{err_msg}。\n\n"
                f"您可尝试输入更简短的品类词（如：`钢材`、`水泥`、`管材`、`坯布`）。",
                trace
            )

        data = res.get("data") or {}
        items = data.get("items") or []
        kw = params.get("keyword")

        if not items:
            return (
                f"{_FALLBACK_BANNER}"
                f"在商城中未找到与 “**{kw}**” 直接匹配的在售工业品。\n\n"
                f"💡 建议尝试检索以下工业建材与原辅料现货品类：\n"
                f"- `建筑钢材`（螺纹钢、盘条）\n"
                f"- `水泥建材`（硅酸盐水泥）\n"
                f"- `工业管材`（PPR给水管）\n"
                f"- `纺织原料`（全棉坯布、涤纶短纤）",
                trace
            )

        msg = (
            f"{_FALLBACK_BANNER}"
            f"为您检索到与 “**{kw}**” 相关的商城现货工业品推荐：\n\n"
            f"| 商品ID | 品名与规格 | 分类/品牌 | 现货单价 | 库存状态 | 认证商家 |\n"
            f"| :--- | :--- | :--- | :--- | :--- | :--- |\n"
        )
        for item in items:
            pid = item.get("prodId")
            title = item.get("title", "")
            spec = item.get("spec", "-")
            cat = item.get("catName", "-")
            brand = item.get("brand", "-")
            price = item.get("price", 0.0)
            stock = item.get("stock", 0)
            status = "充足" if stock > 100 else ("紧张" if stock > 0 else "缺货")
            supplier = "⭐ 认证企业" if item.get("verifiedSupplier") else "直营"
            msg += f"| `{pid}` | **{title}**<br><sub>规格: {spec}</sub> | {cat} / {brand} | ¥{price:,.2f} | {status} ({stock}) | {supplier} |\n"

        first_pid = items[0].get("prodId")
        msg += (
            f"\n\n---\n"
            f"👉 **下一步操作**：\n"
            f"- 获取批量阶梯询价：请回复 `计算商品 {first_pid} 买 500 件的价格`\n"
            f"- 查看指定商品详情：请回复 `查询商品ID {first_pid} 详细信息`"
        )
        return msg, trace

    def handle_policy(self, query: str, require_high_confidence: bool = False) -> Optional[str]:
        """离线知识库规章与政策检索"""
        if not self._offline_index:
            return None
        min_score = 0.15 if require_high_confidence else 0.08
        hits = self._offline_index.search(query, top_k=2, min_score=min_score)
        if not hits:
            return None

        best = hits[0]
        # 排除模糊字符召回或低于阈值的结果，避免无关闲聊被误判为政策问答
        if best.get("fuzzy") or best.get("score", 0) < min_score:
            return None

        title = best.get("title", "官方政策")
        cat = best.get("category", "常见问答")
        content = best.get("content", "")

        msg = (
            f"{_FALLBACK_BANNER}"
            f"### 📖 【政策条款问答】《{title}》\n"
            f"**所属业务分类**：`{cat}`\n\n"
            f"{content}\n\n"
            f"---\n"
            f"*注：以上内容来自 LANLink 供应链离线规范库。如遇特殊大宗合同条款，请联系大客户专属经理（工作日 9:00-18:00）。*"
        )
        return msg

    def handle_general_guide(self, query: str) -> str:
        """通用兜底指引"""
        return (
            f"{_FALLBACK_BANNER}"
            f"当前系统正处于离线安全保护模式。已为您启动启发式确定性业务引擎。\n\n"
            f"您可以直接输入以下标准指令获得即时服务：\n\n"
            f"1. **🔍 工业品选型与推荐**：\n"
            f"   - 输入例：`推荐5款建筑钢材` 或 `搜索管材`\n\n"
            f"2. **💰 阶梯核价与批量询价**：\n"
            f"   - 输入例：`计算商品 1 买 500 件的价格` 或 `商品 2 询价 100 个`\n\n"
            f"3. **🚚 履约跟踪与物流 SLA**：\n"
            f"   - 输入例：`查询订单 20261022170813519896 的物流`\n\n"
            f"4. **📑 商城政策与售后指南**：\n"
            f"   - 输入例：`商城的退换货与质保售后政策是什么？`"
        )

    # ----------------------- 统一执行入口 -----------------------

    def handle(self, query: str, messages: Optional[List[Dict[str, Any]]] = None,
               role: str = "buyer", kb: Any = None) -> Dict[str, Any]:
        """统一降级入口。
        返回：{
            "text": str,
            "intent": str,
            "tool_trace": list,
            "fallback": "heuristic",
            "confidence": float
        }
        """
        raw_query = query.strip()
        if not raw_query and messages:
            for m in reversed(messages):
                if m.get("role") == "user":
                    raw_query = str(m.get("content") or "").strip()
                    break

        # 清洗 RAG 模板前缀，提取纯粹的用户原始问题
        user_m = re.search(r"(?:用户问题|问题|Question)[:：]\s*(.+)$", raw_query, re.S)
        if user_m:
            raw_query = user_m.group(1).strip()
        else:
            raw_query = re.sub(r"^场景[：:].*?\n", "", raw_query).strip()

        trace: List[Dict[str, Any]] = []

        # 1. 尝试识别阶梯询价
        quot_params = self.extract_quotation_intent(raw_query)
        if quot_params:
            text, trace = self.handle_quotation(quot_params)
            return {
                "text": text,
                "intent": "quotation",
                "tool_trace": trace,
                "fallback": "heuristic",
                "confidence": 0.95
            }

        # 2. 尝试识别物流追踪
        logistics_params = self.extract_logistics_intent(raw_query)
        if logistics_params and (logistics_params.get("orderNo") or "物流" in raw_query or "运单" in raw_query):
            text, trace = self.handle_logistics(logistics_params)
            return {
                "text": text,
                "intent": "logistics",
                "tool_trace": trace,
                "fallback": "heuristic",
                "confidence": 0.90
            }

        # 3. 政策问答优先判定（当 query 明显包含政策/售后/退换/保修/规则特征时）
        has_policy_trigger = any(t in raw_query for t in POLICY_TRIGGERS)
        if has_policy_trigger:
            policy_text = self.handle_policy(raw_query, require_high_confidence=False)
            if policy_text:
                return {
                    "text": policy_text,
                    "intent": "policy_faq",
                    "tool_trace": [],
                    "fallback": "heuristic",
                    "confidence": 0.88
                }

        # 4. 尝试识别商品推荐与搜索
        search_params = self.extract_search_intent(raw_query)
        if search_params and search_params.get("keyword"):
            text, trace = self.handle_search(search_params)
            return {
                "text": text,
                "intent": "product_search",
                "tool_trace": trace,
                "fallback": "heuristic",
                "confidence": 0.85
            }

        # 5. 再次尝试高置信度离线知识库检索
        policy_text = self.handle_policy(raw_query, require_high_confidence=True)
        if policy_text:
            return {
                "text": policy_text,
                "intent": "policy_faq",
                "tool_trace": [],
                "fallback": "heuristic",
                "confidence": 0.80
            }

        # 6. 通用指引兜底
        return {
            "text": self.handle_general_guide(raw_query),
            "intent": "general_guide",
            "tool_trace": [],
            "fallback": "heuristic",
            "confidence": 0.50
        }


# 全局单例引擎
FALLBACK_ENGINE = HeuristicFallbackEngine()
