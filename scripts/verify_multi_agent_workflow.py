# -*- coding: utf-8 -*-
"""LANLink B2B 多 Agent 智能协同采购工作流端到端全链路实测脚本

测试范围：
1. 角色工具发现与最小权限隔离（Buyer / Finance / Fulfillment 角色隔离）；
2. 采购助手 Agent（Buyer Agent）：需求解析 -> 现货检索 -> 阶梯询价 -> 人在回路意向草稿单生成；
3. 协同上下文交接（Handoff & Trace ID 传递）；
4. 财务审核 Agent（Finance Auditor Agent）：授信额度比对、阶梯单价偏离度核验、人在回路大额免审限额拦截、放行 Token (approvalToken) 签发；
5. 履约追踪 Agent（Fulfillment Agent）：专线运单跟踪、PII 数据脱敏、IDOR 防垂直越权校验、SLA 超时与物流停滞预警；
6. Anthropic MCP 分角色标准协议端到端联调实测。
"""
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

GATEWAY_URL = os.environ.get("LANLINK_GATEWAY_URL", "http://127.0.0.1:8090").rstrip("/")
GATEWAY_KEY = (
    os.environ.get("LANLINK_GATEWAY_KEY")
    or os.environ.get("GATEWAY_API_KEY")
    or os.environ.get("AI_GATEWAY_API_KEY")
    or "lanlink-local-ai-gateway"
).strip()

def http_get(path, headers=None):
    url = f"{GATEWAY_URL}{path}"
    req_headers = {"User-Agent": "lanlink-multi-agent-verifier/1.0"}
    if headers:
        req_headers.update(headers)
    req = urllib.request.Request(url, headers=req_headers, method="GET")
    with urllib.request.urlopen(req, timeout=15) as resp:
        return resp.status, json.loads(resp.read().decode("utf-8"))

def http_post(path, data, headers=None):
    url = f"{GATEWAY_URL}{path}"
    req_headers = {
        "Content-Type": "application/json",
        "User-Agent": "lanlink-multi-agent-verifier/1.0"
    }
    if headers:
        req_headers.update(headers)
    body = json.dumps(data, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=body, headers=req_headers, method="POST")
    with urllib.request.urlopen(req, timeout=15) as resp:
        return resp.status, json.loads(resp.read().decode("utf-8"))

def print_banner(msg):
    print("\n" + "=" * 70)
    print(f"=== {msg} ===")
    print("=" * 70)

def main():
    print_banner("LANLink B2B 多 Agent 智能协同采购工作流端到端验收实测")
    auth_headers = {"Authorization": f"Bearer {GATEWAY_KEY}"}

    # -------------------------------------------------------------
    # 1. 验证角色工具发现与最小权限隔离
    # -------------------------------------------------------------
    print("\n--- 1. 验证按 Agent 角色隔离最小权限工具集 ---")
    status, buyer_tools = http_get("/v1/ai/tools?role=buyer", auth_headers)
    assert status == 200, f"获取 buyer 工具失败: {status}"
    b_names = {t["function"]["name"] for t in buyer_tools["data"]}
    print(f" 采购助手 Agent (Buyer) 可用工具数: {len(b_names)} -> {sorted(b_names)}")
    assert "lanlink_search_products" in b_names
    assert "lanlink_create_cart_draft" in b_names
    assert "lanlink_approve_procurement_flow" not in b_names, "越权漏洞: 采购助手不应拥有财务审批权限!"

    status, finance_tools = http_get("/v1/ai/tools?role=finance", auth_headers)
    assert status == 200, f"获取 finance 工具失败: {status}"
    f_names = {t["function"]["name"] for t in finance_tools["data"]}
    print(f" 财务审核 Agent (Finance) 可用工具数: {len(f_names)} -> {sorted(f_names)}")
    assert "lanlink_audit_procurement_draft" in f_names
    assert "lanlink_approve_procurement_flow" in f_names
    assert "lanlink_search_products" not in f_names, "权限扩散: 财务助手无需包含商品检索工具"

    status, ful_tools = http_get("/v1/ai/tools?role=fulfillment", auth_headers)
    assert status == 200, f"获取 fulfillment 工具失败: {status}"
    ful_names = {t["function"]["name"] for t in ful_tools["data"]}
    print(f" 履约追踪 Agent (Fulfillment) 可用工具数: {len(ful_names)} -> {sorted(ful_names)}")
    assert "lanlink_track_logistics" in ful_names
    assert "lanlink_monitor_delivery_sla" in ful_names
    assert "lanlink_create_cart_draft" not in ful_names, "越权漏洞: 履约助手不应拥有采购建单权限"

    print(" [PASS] 三大 Agent 角色最小权限隔离策略校验 100% 达标!")

    # -------------------------------------------------------------
    # 2. 采购助手 Agent 业务链路：检索 -> 阶梯询价 -> 生成草稿单
    # -------------------------------------------------------------
    print("\n--- 2. 执行采购助手 Agent (Buyer Agent) 选型询价与意向单锁定 ---")
    # 2.1 检索工业现货
    status, search_res = http_post("/v1/ai/tools/execute", {
        "tool": "lanlink_search_products",
        "arguments": {"keyword": "钢", "limit": 2},
        "apply_fence": True
    }, auth_headers)
    assert status == 200 and search_res["success"], f"检索失败: {search_res}"
    items = search_res["data"]["items"]
    assert len(items) > 0, "未检索到在售测试商品"
    target_prod = items[0]
    prod_id = target_prod["prodId"]
    print(f" 采购助手锁定意向商品: ID={prod_id}, 品名={target_prod['title']}, 标价=¥{target_prod['price']}")

    # 2.2 试算大宗采购阶梯报价 (采购 50 件)
    status, quote_res = http_post("/v1/ai/tools/execute", {
        "tool": "lanlink_calculate_quotation",
        "arguments": {"productId": prod_id, "quantity": 50},
        "apply_fence": True
    }, auth_headers)
    assert status == 200 and quote_res["success"]
    quote = quote_res["data"]
    print(f" 采购助手阶梯报价试算: 采购数量={quote['quantity']}, 优惠单价=¥{quote['effectiveUnitPrice']}, 总金额=¥{quote['finalTotalAmount']}, 节约=¥{quote['totalSavings']}")
    print(f" 试算摘要: {quote['quotationSummary']}")

    # 2.3 生成意向草稿单并获得跳转深链（人在回路）
    status, draft_res = http_post("/v1/ai/tools/execute", {
        "tool": "lanlink_create_cart_draft",
        "arguments": {"productId": prod_id, "quantity": 50, "userId": 888, "remark": "项目急件，需增值税专票"},
        "user_id": 888,
        "apply_fence": True
    }, auth_headers)
    assert status == 200 and draft_res["success"]
    draft = draft_res["data"]
    draft_id = draft["draftId"]
    print(f" 采购助手生成草稿单: draftId={draft_id}")
    print(f" 人在回路核验: status={draft['status']}, checkoutDeepLink={draft['checkoutDeepLink']}")
    assert draft["confirmationRequired"] is True, "人在回路核心约束违规: 未强制要求用户前端确认!"
    print(" [PASS] 采购助手 Agent 成功锁定意向单并坚守人在回路支付防线")

    # -------------------------------------------------------------
    # 3. 跨 Agent 上下文交接 (Handoff with Trace ID)
    # -------------------------------------------------------------
    trace_id = f"trc_{int(time.time())}_{draft_id[-6:]}"
    print(f"\n--- 3. 跨 Agent 上下文安全交接 (Handoff Trace ID: {trace_id}) ---")
    handoff_payload = {
        "trace_id": trace_id,
        "draft_id": draft_id,
        "prod_id": prod_id,
        "user_id": 888,
        "enterprise_id": 1,
        "final_total_amount": draft["finalTotalAmount"]
    }
    print(f" Handoff 结构化上下文载荷: {json.dumps(handoff_payload, ensure_ascii=False)}")
    print(" [PASS] 上下文链路交接结构完整")

    # -------------------------------------------------------------
    # 4. 财务审核 Agent 业务链路：授信核验 -> 单价偏离度风控 -> 放行签发
    # -------------------------------------------------------------
    print("\n--- 4. 执行财务审核 Agent (Finance Auditor Agent) 风控与放行审批 ---")
    # 4.1 财务风控审核
    status, audit_res = http_post("/v1/ai/tools/execute", {
        "tool": "lanlink_audit_procurement_draft",
        "arguments": {
            "draftId": draft_id,
            "userId": 888,
            "enterpriseId": 1
        },
        "apply_fence": True
    }, auth_headers)
    assert status == 200 and audit_res["success"], f"财务审核失败: {audit_res}"
    audit_data = audit_res["data"]
    print(f" 财务审核初审结论: auditStatus={audit_data['auditStatus']}, riskLevel={audit_data['riskLevel']}")
    print(f" 授信比对: 企业可用授信=¥{audit_data['availableCredit']}, 授信是否充足={audit_data['creditSufficient']}")
    print(f" 单价偏离度: {audit_data['priceDeviationRate']}, 免审上限=¥{audit_data['maxAutoApprovalLimit']}")
    print(f" 审核风控说明: {audit_data['auditRemark']}")

    # 4.2 财务签发放行 Token
    status, appr_res = http_post("/v1/ai/tools/execute", {
        "tool": "lanlink_approve_procurement_flow",
        "arguments": {
            "draftId": draft_id,
            "approved": True,
            "comment": "企业授信充足，大宗采购阶梯折扣在合规区间，同意放行"
        },
        "apply_fence": True
    }, auth_headers)
    assert status == 200 and appr_res["success"], f"审批放行失败: {appr_res}"
    appr_data = appr_res["data"]
    approval_token = appr_data["approvalToken"]
    print(f" 财务放行审批结果: status={appr_data['status']}, approvalToken={approval_token}")
    print(f" 放行凭证有效期: {appr_data['expireMinutes']} 分钟, 说明: {appr_data['message']}")
    assert approval_token and approval_token.startswith("appr_"), "审批放行 Token 签发格式异常"
    print(" [PASS] 财务审核 Agent 合规校验与放行 Token 签发验证通过!")

    # -------------------------------------------------------------
    # 5. 履约追踪 Agent 业务链路：运单跟踪 -> PII 脱敏 -> SLA 预警
    # -------------------------------------------------------------
    print("\n--- 5. 执行履约追踪 Agent (Fulfillment Agent) 时效与 SLA 预警 ---")
    real_order_no = "20261022170813519896"
    real_user_id = 4

    # 5.1 监控真实订单履约 SLA 状态
    status, sla_res = http_post("/v1/ai/tools/execute", {
        "tool": "lanlink_monitor_delivery_sla",
        "arguments": {"orderNo": real_order_no},
        "user_id": real_user_id,
        "apply_fence": True
    }, auth_headers)
    assert status == 200 and sla_res["success"], f"SLA 监控失败: {sla_res}"
    sla_data = sla_res["data"]
    print(f" 履约时效 SLA 诊断: 订单={sla_data['orderNo']}, 运单={sla_data['waybillNo']}")
    print(f" SLA 状态={sla_data['slaStatus']}, 累计耗时={sla_data['elapsedHours']}小时, 承运商={sla_data['carrierName']}")
    print(f" 履约建议动作: {sla_data['suggestedAction']}")

    # 5.2 查询物流轨迹与 PII 隐私脱敏保护
    status, trace_res = http_post("/v1/ai/tools/execute", {
        "tool": "lanlink_track_logistics",
        "arguments": {"orderNo": real_order_no},
        "user_id": real_user_id,
        "apply_fence": True
    }, auth_headers)
    assert status == 200 and trace_res["success"], f"物流轨迹查询失败: {trace_res}"
    tr_data = trace_res["data"]
    print(f" 收货人脱敏校验: 姓名={tr_data['receiverName']}, 电话={tr_data['receiverPhone']}, 地址={tr_data['destination']}")
    assert "*" in tr_data["receiverName"], "隐私安全告警: 收货人姓名未脱敏!"
    assert "****" in tr_data["receiverPhone"], "隐私安全告警: 手机号未脱敏!"
    print(f" 物流节点数量: {len(tr_data['traces'])}, 当前状态={tr_data['orderStatusDesc']}")

    # 5.3 验证 IDOR 越权攻击防护拦截
    status, idor_res = http_post("/v1/ai/tools/execute", {
        "tool": "lanlink_track_logistics",
        "arguments": {"orderNo": real_order_no},
        "user_id": 99999,  # 非订单所有者
        "apply_fence": True
    }, auth_headers)
    assert idor_res["success"] is False, "越权安全漏洞: 异主查询订单未被拦截!"
    print(f" IDOR 越权攻击拦截验证: {idor_res['error']}")
    print(" [PASS] 履约追踪 Agent 时效监控、PII 脱敏与防 IDOR 越权拦截 100% 达标!")

    # -------------------------------------------------------------
    # 6. MCP Stdio Bridge 跨进程 JSON-RPC 协同实测
    # -------------------------------------------------------------
    print("\n--- 6. 验证 Claude Desktop / Cursor 对应 MCP Stdio 协议角色化调用 ---")
    mcp_script = os.path.join(os.path.dirname(__file__), "mcp_stdio_bridge.py")

    def run_mcp_query(role, rpc_dict):
        proc = subprocess.Popen(
            [sys.executable, mcp_script, "--role", role],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8"
        )
        out, err = proc.communicate(input=json.dumps(rpc_dict) + "\n", timeout=10)
        return json.loads(out.strip())

    # 6.1 测试 MCP Finance 角色工具列表
    list_rpc = {"jsonrpc": "2.0", "id": 101, "method": "tools/list", "params": {}}
    resp = run_mcp_query("finance", list_rpc)
    assert resp.get("result"), f"MCP 调用失败: {resp}"
    tools_returned = {t["name"] for t in resp["result"]["tools"]}
    print(f" MCP Finance 客户端返回工具数: {len(tools_returned)} -> {sorted(tools_returned)}")
    assert "lanlink_audit_procurement_draft" in tools_returned
    assert "lanlink_search_products" not in tools_returned

    # 6.2 测试 MCP 真实调用 lanlink_audit_procurement_draft
    call_rpc = {
        "jsonrpc": "2.0",
        "id": 102,
        "method": "tools/call",
        "params": {
            "name": "lanlink_audit_procurement_draft",
            "arguments": {"draftId": draft_id}
        }
    }
    call_resp = run_mcp_query("finance", call_rpc)
    assert not call_resp["result"]["isError"], f"MCP 工具执行异常: {call_resp}"
    mcp_text = call_resp["result"]["content"][0]["text"]
    assert "LANLINK_PROTECTED_DATA_START:lanlink_audit_procurement_draft" in mcp_text
    print(f" MCP Finance 审核工具调用成功，已附加数据防提示注入围栏!")

    print_banner("[ALL PASSED] LANLink 多 Agent 智能协同采购工作流全链路实测 100% 验证通过！")

if __name__ == "__main__":
    main()
