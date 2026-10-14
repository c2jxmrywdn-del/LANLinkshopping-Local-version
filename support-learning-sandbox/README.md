# LANLinkshopping 客服学习 Docker 沙盒

这是一个**独立、无状态的候选知识评估容器**，由生产管理 API 通过 Railway 私有网络调用。它不承载商城 API，不连接生产 MySQL，不挂载主机目录，不保存对话、草稿或评估结果，也不执行用户提供的代码。

## 容器边界

- 使用 Python 标准库运行，不安装第三方运行时依赖。
- 以非 root 用户启动；镜像构建阶段运行单元测试。
- 容器只提供 **GET /health** 和受认证保护的 **POST /v1/evaluate**。
- X-Sandbox-Token 必须配置，且至少 40 个字符；缺失或不匹配时拒绝评估。
- 每个请求限制为 16 KiB；关键词数量、问题、条目 ID 与回答长度均有上限，并设置每来源限流。
- 不调用 LLM、外部 API、Shell、数据库或动态代码执行；不输出请求体或回答到访问日志。
- 生产容器不添加公网域名；只让主后端通过 *.railway.internal 访问。
- Docker Compose 本地配置启用只读根文件系统、丢弃 Linux capabilities、禁止提权、限制 CPU/内存/PID，并使用内部网络；端口只绑定到本机回环地址。
- 容器只负责候选回答安全与发布条件的隔离校验。当前正式意图、知识回答和重复关键词信号仍由受控的 Spring Boot 知识匹配器生成，容器不接触生产数据库。

## 本地 Docker 检查

需要 Docker Engine 和 Docker Compose v2。先设置临时测试令牌（不要使用生产令牌）：

~~~sh
export SUPPORT_SANDBOX_TOKEN="$(python -c 'import secrets; print(secrets.token_urlsafe(48))')"
docker compose -f support-learning-sandbox/docker-compose.yml up --build -d
curl http://127.0.0.1:18080/health
~~~

向 POST http://127.0.0.1:18080/v1/evaluate 发送 JSON 时，需加入 X-Sandbox-Token 请求头。测试结束运行：

~~~sh
docker compose -f support-learning-sandbox/docker-compose.yml down
~~~

## Railway 私有部署

服务根目录设置为 /support-learning-sandbox，Dockerfile 路径为 Dockerfile，监听端口为 8080。设置以下服务变量：

- PORT=8080
- SUPPORT_SANDBOX_TOKEN：随机生成的至少 40 字符秘密值，需与主后端服务中的值完全一致。

为沙盒配置私有网络端点名 lanlink-learning-sandbox。不要生成公网域名，不挂载卷，也不要添加数据库或生产支付、邮件、用户认证等变量。建议先从 0.25 vCPU / 0.25 GB 内存、单副本开始。

主后端服务变量：

- SUPPORT_SANDBOX_URL=http://lanlink-learning-sandbox.railway.internal:8080
- SUPPORT_SANDBOX_TOKEN：与沙盒容器同一令牌。

管理端访问仍由主后端管理员鉴权与 CSRF 防护负责。默认 SUPPORT_SANDBOX_MODE=container：若容器地址、密钥或容器服务不可用，预检接口会返回 503，不会静默降级。只有明确将 SUPPORT_SANDBOX_MODE 设置为 local 才启用临时进程内模式；该模式会显示非隔离警示并禁用候选导出。正式容器运行后应切回 container。

## 安全提醒

令牌只通过 Railway 服务变量设置，不提交到 Git、不写进前端、不加入 Docker 镜像。不要把此服务暴露到公共网络。沙盒是候选内容验证环境，不是自动训练或自动发布系统；通过预检仍需管理员人工审核。
