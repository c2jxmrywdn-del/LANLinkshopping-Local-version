# MCP fetch 工具桥：HTTP(streamable) → stdio(mcp-server-fetch) 的极薄转发层。
# 官方 mcp-server-fetch 只提供 stdio 传输，这里用 MCP Python SDK 把它挂到
# /mcp streamable HTTP 端点供 ai-gateway 内网调用；/healthz 供 compose 健康探活。
# 低层 Server(on_list_tools/on_call_tool) + streamable_http_app 在 2.x SDK 实测可用；
# 注意 uvicorn 必须与 stdio 子进程同一事件循环（子线程双循环会跨循环挂死，见探针记录）。
# 基底用 daocloud 代理前缀：本机直连 docker.io 超时（实测 auth.docker.io token 拉取失败）；
# 仓库既有镜像同惯例（docker-compose.yml redis 用 docker.m.daocloud.io 前缀，
# 本机已有 docker.m.daocloud.io/library/python:3.12-alpine 缓存佐证可达）。
# python:3.12-slim 与代理镜像内容一致（官方镜像的字节级代理）。
FROM docker.m.daocloud.io/library/python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY app.py ./

# 沿用 ai-gateway/Dockerfile:29 的非 root 惯例
RUN addgroup --system bridge && adduser --system --uid 10001 --ingroup bridge bridge
USER 10001:10001

EXPOSE 8781
HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=3 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8781/healthz', timeout=3).read()"

CMD ["python", "app.py"]
