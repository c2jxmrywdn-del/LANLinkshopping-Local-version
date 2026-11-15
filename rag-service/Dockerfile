# Python 3.14 是刻意选择，不是笔误。
#
# raganything 的依赖声明里，MinerU 带 `python_version < "3.14"` 条件：
#  - 3.12/3.13 → 安装 mineru[core] → 拉入 torch + CUDA 13，site-packages 6.3GB
#  - 3.14      → 自动跳过整个 CUDA/torch 依赖树，site-packages 约 334MB
# 本机 RTX 4070 仅剩 ~536MiB 显存，装 GPU parser 既跑不动也白占磁盘。
FROM python:3.14-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /app

# 先装依赖再拷代码：代码改动不会让依赖层失效
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app

# 索引落盘目录（compose 挂卷持久化）
RUN mkdir -p /data/rag_storage

# 非 root 运行
RUN useradd -m -u 10001 rag && chown -R rag:rag /app /data
USER rag

EXPOSE 8093

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8093/health', timeout=4).status==200 else 1)"

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8093"]
