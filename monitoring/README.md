# monitoring/ 可观测性栈

| 目录 | 用途 | 宿主访问 |
|:---|:---|:---|
| `prometheus/` | 抓取配置（`prometheus.yml`）与告警规则（`alert_rules.yml`） | http://127.0.0.1:9090 |
| `alertmanager/` | 告警路由与三通道投递（`alertmanager.yml`） | http://127.0.0.1:9093 |
| `grafana/` | 看板 JSON 与数据源/看板预置（datasource 指向 `http://prometheus:9090`） | 根 compose 未编排 grafana 服务，需自建实例加载本目录 |
| `feishu-adapter/` | 飞书自建应用告警适配器（stdlib Python，无第三方依赖） | http://127.0.0.1:8061 |

容器由根 `docker-compose.yml` 编排，服务名 `prometheus` / `alertmanager` / `dingtalk-webhook-adapter` / `feishu-webhook-adapter`。

## 告警三通道

`monitoring/alertmanager/alertmanager.yml` 里每条告警同时进三个 receiver：

1. **邮件** — `ops-email`，经 Mailpit（无认证 SMTP）投递，网页收件箱 http://127.0.0.1:8081 可见。
2. **IM（钉钉/企微）** — `ops-webhook`，POST 到 `http://dingtalk-webhook-adapter:8060/dingtalk/ops/send`，
   由 `dingtalk-webhook-adapter` 容器转换成钉钉/企微机器人要求的 **text 格式**后再外发。
3. **IM（飞书）** — `ops-feishu`，POST 到 `http://feishu-webhook-adapter:8061/feishu/ops/send`，
   由 `feishu-webhook-adapter` 容器用飞书自建应用（tenant_access_token）发 text 消息到告警群。

### 填写机器人 webhook

1. 在根 `.env`（已 gitignore）追加一行，填机器人地址（**不要提交到仓库**）：

   ```env
   DINGTALK_WEBHOOK_URL=https://oapi.dingtalk.com/robot/send?access_token=你的access_token
   ```

   企业微信群机器人则填 `https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=你的key`。
   留空时 compose 用占位地址兜底，适配器能起但外发失败，邮件通道不受影响。

2. 让适配器重建以读取新地址：

   ```bash
   docker compose up -d dingtalk-webhook-adapter
   ```

> 地址不作为容器环境变量注入：该镜像内无 shell 也不展开配置里的 `$VAR`（实测报 `unsupported scheme ""`），
> 因此由 compose 在宿主机侧把 `.env` 的值渲染进 `configs.content` 再挂载给容器。

### 填写飞书自建应用（飞书通道）

1. 飞书开放平台创建**企业自建应用**，拿 App ID（`cli_` 开头）与 App Secret；开通 `im:message`
   发消息权限并**发布版本**（未发布的应用机器人加不进群）。
2. 根 `.env`（已 gitignore）填三行（**不要提交到仓库**）：

   ```env
   FEISHU_APP_ID=cli_xxxx
   FEISHU_APP_SECRET=xxxx
   FEISHU_CHAT_ID=
   ```

3. 把应用机器人添加进告警群：群设置 → 群机器人 → 添加应用机器人。
4. 发现群 chat_id 并回填：

   ```bash
   curl http://127.0.0.1:8061/feishu/health   # 返回机器人所在群列表（chat_id + 群名）
   # 把目标群 chat_id 填进 .env 的 FEISHU_CHAT_ID 后重建：
   docker compose up -d feishu-webhook-adapter
   ```

   `FEISHU_CHAT_ID` 留空时适配器照常启动，告警外发会跳过并在健康端点/日志里列出可选群（邮件通道不受影响）。
5. 手测一条消息（Alertmanager JSON 与 `{"text": ...}` 透传均可）：

   ```bash
   curl -X POST http://127.0.0.1:8061/feishu/ops/send -H 'Content-Type: application/json' \
     -d '{"text":"告警通道自测"}'
   ```

> 飞书凭据同样经 compose `configs.content` 宿主机侧渲染进容器（不进容器环境变量），与钉钉适配器同纪律。

### 自测一条告警

Alertmanager 提供 HTTP API，可直接注入一条假告警观察两条通道：

```bash
curl -X POST http://127.0.0.1:9093/api/v2/alerts \
  -H 'Content-Type: application/json' \
  -d '[{"labels":{"alertname":"SelfTest","severity":"warning"},"annotations":{"summary":"告警通道自测"}}]'
```

- 邮件通道：约 30 秒后（`group_wait: 30s`）在 http://127.0.0.1:8081 看到 "SelfTest" 邮件。
- 钉钉/企微通道：同一时刻机器人所在群里收到 text 消息；未填真实 webhook 时这里会失败，
  可用 `docker logs lanlink-dingtalk-webhook-adapter` 查看外发报错。
- 飞书通道：`docker logs lanlink-feishu-webhook-adapter` 看 `delivered message_id=...`；
  chat_id 未配置时日志会列出机器人所在群。

验证路由本身（不发通知，只看命中哪些 receiver）：

```bash
docker run --rm --entrypoint amtool \
  -v "$(pwd)/monitoring/alertmanager/alertmanager.yml:/etc/alertmanager/alertmanager.yml:ro" \
  prom/alertmanager:v0.27.0 config routes test \
  --config.file=/etc/alertmanager/alertmanager.yml severity=warning alertname=SelfTest
# 期望输出：ops-email,ops-webhook,ops-feishu
```

### 校验配置

```bash
# Alertmanager 配置
docker run --rm --entrypoint amtool \
  -v "$(pwd)/monitoring/alertmanager/alertmanager.yml:/etc/alertmanager/alertmanager.yml:ro" \
  prom/alertmanager:v0.27.0 check-config /etc/alertmanager/alertmanager.yml

# 告警规则
docker run --rm --entrypoint promtool \
  -v "$(pwd)/monitoring/prometheus/alert_rules.yml:/rules.yml:ro" \
  prom/prometheus:v2.55.1 check rules /rules.yml
```

### 抓取目标说明

`prometheus.yml` 里的 target 一律用 **compose 服务名**（`ai-gateway` / `backend` / `rabbitmq`），
不写容器名（`lanlink-ai-gateway` / `shopping-backend-1`）：容器名随 compose 项目名与实例序号变化，
服务名在 `shopping_default` 网络里由 Docker DNS 稳定解析。
