# LANLink AI 网关沙盒镜像：OpenAI 兼容网关 + 本地知识库 RAG
FROM python:3.12-alpine

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PORT=8090 \
    OLLAMA_BASE_URL=http://ollama:11434 \
    LLM_MODEL=qwen2.5:0.5b \
    KB_PATH=/app/kb_data/ecommerce_kb.json

RUN addgroup -S gateway && adduser -S -D -H -u 10001 -G gateway gateway
WORKDIR /app

COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app
COPY kb_data ./kb_data
COPY tests ./tests

# 构建期自检：单元测试 + 编译校验，保证镜像内代码可运行
RUN python -m unittest discover -s tests -v \
    && python -m py_compile app/*.py

USER 10001:10001
EXPOSE 8090
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD python -c "import os,urllib.request; urllib.request.urlopen('http://127.0.0.1:'+os.environ.get('PORT','8090')+'/health',timeout=3).read()"

CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8090"]
