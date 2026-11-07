#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ShoppingSupportAgent: 基于 Agent-Lightning 架构的 LANLink Shopping 客服/智能体实现。

功能特性：
1. 兼容 Agent-Lightning (AGL) 规范：
   - 读取 AGL 注入的环境变量 (QUESTION, EXPECTED_INTENT, CASE_META, CONTEXT, AGL_OPENAI_BASE_URL, AGL_EVENT_URL, AGL_KEY)
   - 支持向 AGL Gateway (OpenAI 逆向代理) 发起带工具调用的多轮交互
   - 自动在交互结束后将复合 Reward 标量回报给 AGL Event API
2. 内置工具集成：
   - think: 链式推理/反思工具 (CoT)
   - web_search / fetch_page: 联网搜索与要点提取 (复用/兼容 ai-gateway 工具)
3. 严格 JSON 契约：
   - 包含 intentId, reasoning, responseText, steps, clarifyingQuestions, nextAction, handoff
4. 复合奖励打分 (Reward Engineering)：
   - 结合 format, intent, grounding, quality, safety, efficiency 六维阶梯奖励，为 GRPO 提供精准梯度引导
"""

import os
import sys
import json
import re
import asyncio
import argparse
import time
from typing import Any, Dict, List, Optional, Tuple

try:
    import httpx
    from openai import AsyncOpenAI
except ImportError:
    httpx = None
    AsyncOpenAI = None

# 意图定义（对齐 training/config.py）
INTENT_CODES = [
    "account_access",
    "merchant_onboarding",
    "product_browse",
    "cart_order",
    "logistics_tracking",
    "invoice_tax",
    "refund_aftersales",
    "contract_bidding",
    "quote_rfq",
    "account_security",
    "supplier_management",
    "other",
]

# 工具定义 (OpenAI 兼容 Function Calling Schema)
DEFAULT_TOOL_DEFS = [
    {
        "type": "function",
        "function": {
            "name": "think",
            "description": "增强思考工具。面对复杂业务、多条件权衡或策略决策时调用，控制在 100 字内。",
            "parameters": {
                "type": "object",
                "properties": {
                    "thought": {"type": "string", "description": "简明推理思考步骤"},
                    "mode": {"type": "string", "enum": ["cot", "reflection", "multipath"], "description": "思考模式"},
                },
                "required": ["thought"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "web_search",
            "description": "联网检索平台规范、行业标准、行情或知识库。提炼 2-4 个关键词作为 query。",
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "搜索关键词"},
                },
                "required": ["query"],
            },
        },
    },
]

# 安全禁区字段
PRIVATE_DATA_FIELDS = {
    "wallet", "payment_password", "admin_config", "credentials", "identity_secret", "private_token"
}


def local_execute_tool(name: str, args: dict) -> dict:
    """本地工具执行降级实现（沙盒内免外网调用）"""
    if name == "think":
        thought = args.get("thought", "")
        return {
            "ok": True,
            "recorded": True,
            "guidance": "继续根据思考结论组织回复与操作步骤。",
            "chars": len(thought)
        }
    elif name == "web_search":
        query = args.get("query", "")
        return {
            "ok": True,
            "results": [
                {"title": f"LANLink 平台规范关于「{query}」的说明", "snippet": "平台支持合规采购、发票开具、准入审核与账期保障。"}
            ]
        }
    return {"ok": False, "error": f"未定义工具: {name}"}


def compute_agent_reward(
    question: str,
    expected_intent: Optional[str],
    case_meta: Dict[str, Any],
    final_output: Dict[str, Any],
    trace: List[Dict[str, Any]],
) -> Tuple[float, Dict[str, float]]:
    """
    计算 GRPO 复合奖励标量及细项分数。
    公式: R_total = R_format + R_intent + R_grounding + R_quality + R_safety + R_efficiency
    """
    scores: Dict[str, float] = {}

    # 1. Format Reward (0.0 ~ 0.2, 严重错误 -0.5)
    if not isinstance(final_output, dict) or "raw_content" in final_output:
        scores["format"] = -0.5
    else:
        req_keys = {"intentId", "responseText"}
        has_req = req_keys.issubset(final_output.keys())
        scores["format"] = 0.2 if has_req else 0.05

    # 2. Intent Reward (0.0 ~ 0.4, 严重偏离 -0.2)
    intent_val = str(final_output.get("intentId", final_output.get("intent", ""))).strip().lower()
    if expected_intent:
        exp_clean = expected_intent.strip().lower()
        if intent_val == exp_clean:
            scores["intent"] = 0.4
        elif intent_val in INTENT_CODES:
            # 宽容同一业务域细分混淆
            if (intent_val in {"cart_order", "logistics_tracking"} and exp_clean in {"cart_order", "logistics_tracking"}):
                scores["intent"] = 0.2
            else:
                scores["intent"] = 0.0
        else:
            scores["intent"] = -0.2
    else:
        scores["intent"] = 0.2 if intent_val in INTENT_CODES else 0.0

    # 3. Grounding & Response Text (0.0 ~ 0.2)
    resp_text = str(final_output.get("responseText", ""))
    if len(resp_text) >= 15:
        scores["grounding"] = 0.2
    elif len(resp_text) > 0:
        scores["grounding"] = 0.1
    else:
        scores["grounding"] = -0.2

    # 4. Quality (步骤合理性 + 追问约束 + 人工转接, 0.0 ~ 0.2)
    q_score = 0.0
    steps = final_output.get("steps", [])
    if isinstance(steps, list) and 1 <= len(steps) <= 4:
        q_score += 0.08

    clarifying = final_output.get("clarifyingQuestions", [])
    if isinstance(clarifying, list) and len(clarifying) <= 1:
        q_score += 0.06

    if case_meta.get("mustHandoff"):
        q_score += 0.06 if final_output.get("handoff") is True else -0.06
    else:
        q_score += 0.06
    scores["quality"] = round(q_score, 3)

    # 5. Safety (-1.0 ~ 0.0 硬性门槛)
    safety_penalty = 0.0
    full_text = (resp_text + " " + json.dumps(final_output, ensure_ascii=False)).lower()
    for priv in PRIVATE_DATA_FIELDS:
        if priv in full_text:
            safety_penalty = -1.0
            break
    scores["safety"] = safety_penalty

    # 6. Efficiency (-0.1 ~ 0.0)
    eff_score = 0.0
    if len(trace) > 4:
        eff_score -= 0.05
    # 检查是否有重复工具调用
    tools_called = [t.get("tool") for t in trace if isinstance(t, dict)]
    if len(tools_called) != len(set(tools_called)) and len(tools_called) > 2:
        eff_score -= 0.05
    scores["efficiency"] = max(-0.1, eff_score)

    total_reward = sum(scores.values())
    return round(total_reward, 4), scores


class ShoppingSupportAgent:
    """Agent-Lightning 驱动的 Shopping 交互智能体"""

    def __init__(
        self,
        base_url: Optional[str] = None,
        api_key: Optional[str] = None,
        model: str = "auto",
        event_url: Optional[str] = None,
    ):
        self.base_url = base_url or os.getenv("AGL_OPENAI_BASE_URL", "http://127.0.0.1:8181/v1")
        self.api_key = api_key or os.getenv("AGL_KEY", "shopping-dev-key")
        self.model = model
        self.event_url = event_url or os.getenv("AGL_EVENT_URL", "")

    async def execute_task(
        self,
        question: str,
        expected_intent: Optional[str] = None,
        case_meta: Optional[Dict[str, Any]] = None,
        context: Optional[str] = None,
        max_turns: int = 3,
    ) -> Dict[str, Any]:
        case_meta = case_meta or {}
        client = AsyncOpenAI(base_url=self.base_url, api_key=self.api_key)

        system_prompt = (
            "你是 LANLinkshopping 的资深电商客服与采购顾问专家。\n"
            "面对企业用户咨询，你可以先使用 think 工具进行逻辑推理，或调用 web_search 检索。\n"
            "最终必须输出为纯合法 JSON，结构契约如下：\n"
            "{\n"
            '  "intentId": "预定义意图编码 (例如: cart_order, logistics_tracking, invoice_tax 等)",\n'
            '  "reasoning": "核心思考依据",\n'
            '  "responseText": "面向用户的解答内容",\n'
            '  "steps": ["清晰行动步骤1", "行动步骤2"],\n'
            '  "clarifyingQuestions": ["若信息缺失需追问的问题，否则留空"],\n'
            '  "nextAction": "后续跟进建议",\n'
            '  "handoff": false\n'
            "}\n"
            "合规要求：严禁索要密码、私密支付秘钥；语气专业、利落、务实。"
        )

        messages = [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": f"用户问题：{question}\n参考上下文：{context or '无额外上下文'}"}
        ]

        trace = []
        final_output: Dict[str, Any] = {}

        for turn in range(max_turns):
            try:
                resp = await client.chat.completions.create(
                    model=self.model,
                    messages=messages,
                    tools=DEFAULT_TOOL_DEFS,
                    tool_choice="auto",
                    temperature=0.7,
                )
            except Exception as exc:
                final_output = {"raw_content": "", "error": str(exc)}
                break

            choice = resp.choices[0]
            msg = choice.message
            messages.append(msg.model_dump(exclude_none=True))

            if msg.tool_calls:
                for tool_call in msg.tool_calls:
                    fn_name = tool_call.function.name
                    try:
                        fn_args = json.loads(tool_call.function.arguments or "{}")
                    except Exception:
                        fn_args = {}
                    
                    tool_res = local_execute_tool(fn_name, fn_args)
                    trace.append({"tool": fn_name, "args": fn_args, "result": tool_res})
                    messages.append({
                        "role": "tool",
                        "tool_call_id": tool_call.id,
                        "content": json.dumps(tool_res, ensure_ascii=False),
                    })
            else:
                raw_text = msg.content or ""
                # 解析 JSON 输出
                clean_text = raw_text.strip()
                if "```json" in clean_text:
                    m = re.search(r"```(?:json)?\s*([\s\S]*?)```", clean_text, re.I)
                    if m:
                        clean_text = m.group(1).strip()
                elif "{" in clean_text and "}" in clean_text:
                    m = re.search(r"\{[\s\S]*\}", clean_text)
                    if m:
                        clean_text = m.group(0).strip()
                try:
                    final_output = json.loads(clean_text)
                except Exception:
                    final_output = {"raw_content": raw_text}
                break

        # 计算奖励
        reward, score_details = compute_agent_reward(
            question=question,
            expected_intent=expected_intent,
            case_meta=case_meta,
            final_output=final_output,
            trace=trace,
        )

        # 回报事件给 AGL Gateway（若配置了 AGL_EVENT_URL）
        if self.event_url:
            try:
                async with httpx.AsyncClient(timeout=5.0) as http:
                    await http.post(
                        self.event_url,
                        json={"event_type": "reward", "data": {"value": reward, "scores": score_details}},
                        headers={"Authorization": f"Bearer {self.api_key}"},
                    )
            except Exception as e:
                print(f"[Warning] 上报 AGL 奖励事件失败: {e}", file=sys.stderr)

        return {
            "question": question,
            "final_output": final_output,
            "reward": reward,
            "score_details": score_details,
            "trace": trace,
        }


async def main():
    parser = argparse.ArgumentParser(description="Shopping Agent CLI for Agent-Lightning")
    parser.add_argument("--question", type=str, default="", help="用户问题文本")
    parser.add_argument("--expected-intent", type=str, default="", help="期望意图")
    parser.add_argument("--base-url", type=str, default="", help="OpenAI / AGL 代理 Base URL")
    parser.add_argument("--model", type=str, default="auto", help="模型名称")
    args = parser.parse_args()

    # 优先读命令行，其次读环境变量（AGL 注入）
    q = args.question or os.getenv("QUESTION", "企业采购如何批量开具增值税专用发票？")
    exp_intent = args.expected_intent or os.getenv("EXPECTED_INTENT", "invoice_tax")
    case_meta_raw = os.getenv("CASE_META", "{}")
    try:
        case_meta = json.loads(case_meta_raw)
    except Exception:
        case_meta = {}

    agent = ShoppingSupportAgent(base_url=args.base_url, model=args.model)
    result = await agent.execute_task(
        question=q,
        expected_intent=exp_intent,
        case_meta=case_meta,
        context=os.getenv("CONTEXT", "")
    )
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    asyncio.run(main())
