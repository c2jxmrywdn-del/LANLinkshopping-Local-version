# 客服对话质量离线回归

## 用途与边界

本目录新增的 conversation_regression_cases.json 是一组去标识化的合成测试场景；conversation_quality.py 会对外部评估器或人工审核导出的结构化候选结果做离线评分。它不调用大模型、不请求生产数据、不执行工具、不保存对话，也不会修改正式知识库。

覆盖四类目标及安全约束：
- 意图误判：区分流程导航、故障排查、多意图澄清与个人实时交易状态。
- 情绪适配：焦急、困惑、挫败问题需要简短具体的共情；中性问题不应使用夸张安慰。
- 连续追问：检查后续回答是否保留上一轮明确主题，避免让用户重复描述。
- 问题解决准备度：检查排查步骤数量、下一步动作、澄清问题数量及转人工标记。
- 知识依据与安全：事实声明关联批准的知识条目；防止未授权的订单/钱包/交易数据访问、虚假退款承诺与提示注入越界。

## 回归测试命令

在此目录运行：

~~~sh
python -m unittest -v
python -m py_compile conversation_quality.py test_conversation_quality.py
python -m json.tool conversation_regression_cases.json > /dev/null
~~~

Dockerfile 构建阶段也会运行单元测试、Python 编译检查和 JSON 格式校验。

## 对候选结果评分

把外部评估器/人工审核输出保存为 JSON 数组；每一项的 caseId 必须对应回归场景的 id。结构化字段包括 intentId、emotion、responseMode、responseText、steps、nextAction、empathyLevel、empathyText、clarifyingQuestions、handoff、contextTopic、dataAccess 和 claims。每个 claims 条目须带 sourceId，并且该 sourceId 必须在场景的 approvedSourceIds 中。

~~~sh
python conversation_quality.py --cases conversation_regression_cases.json --results candidate-results.json --output quality-report.json
~~~

退出码为 0 表示全部输入场景通过；存在失败或缺失的候选结果则返回非 0。报告输出总体通过率、意图准确率、情绪适配率、连续追问衔接率与问题解决准备度，并列出每项失败原因。

## 指标解释

这是一个**结构化质量门禁**，检查候选结果的意图标签、情绪策略、上下文主题、步骤/兜底动作、知识来源和安全字段是否符合人工预期。它本身不会识别自由文本的真实意图，也不能证明回答语义正确、实际解决了用户问题或提高了客户满意度。单元测试中的通过结果是合成轨迹，用于验证评估规则本身；只有将真实候选系统产生的结果导入后，门禁指标才能反映该候选系统在这组测试场景上的表现。上线前仍需人工审核自然语言质量和事实依据。

当前改动只加入测试与评估工具，不修改正式客服匹配器、知识库、数据库权限或线上服务行为。通过门禁不等于自动发布。
