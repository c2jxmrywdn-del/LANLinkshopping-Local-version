# LANLink AI 网关沙盒镜像：OpenAI 兼容网关 + 本地知识库 RAG
# 多阶段构建：test 阶段执行全量 pytest 质量门禁，runtime 阶段不携带测试与测试依赖。
FROM python:3.12-alpine AS deps

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PORT=8090 \
    OLLAMA_BASE_URL=http://ollama:11434 \
    LLM_MODEL=qwen2.5:0.5b \
    KB_PATH=/app/kb_data/ecommerce_kb.json

WORKDIR /app

COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

# ---- 构建期质量门禁：pytest 收集 unittest 与 pytest 两种风格用例 ----
FROM deps AS test
COPY requirements-dev.txt ./
RUN pip install --no-cache-dir -r requirements-dev.txt
COPY app ./app
COPY kb_data ./kb_data
COPY tests ./tests
RUN python -m pytest tests -q \
    && python -m py_compile app/*.py

# ---- 运行镜像：只保留应用代码；app 取自通过测试的 test 阶段，确保门禁必然执行 ----
FROM deps AS runtime
RUN addgroup -S gateway && adduser -S -D -H -u 10001 -G gateway gateway
COPY --from=test /app/app ./app
COPY kb_data ./kb_data

USER 10001:10001
EXPOSE 8090
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD python -c "import os,urllib.request; urllib.request.urlopen('http://127.0.0.1:'+os.environ.get('PORT','8090')+'/health',timeout=3).read()"

CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8090"]
