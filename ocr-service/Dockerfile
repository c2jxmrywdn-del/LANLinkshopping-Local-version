# LANLink OCR 感知服务：FastAPI + RapidOCR(ONNX Runtime)，不含 torch/tensorflow。
# OpenCV(headless) 需要 libgl1/libglib2.0-0；onnxruntime 需要 libgomp1。
# 基础镜像走国内镜像源：Docker Hub 直连在当前环境不可达
ARG BASE_IMAGE=docker.m.daocloud.io/library/python:3.11-slim
FROM ${BASE_IMAGE}

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    HOME=/tmp \
    LOG_LEVEL=INFO

WORKDIR /app

# Debian 源与 pip 源同样走国内镜像，避免构建时卡在 apt/pypi
RUN (sed -i 's|deb.debian.org|mirrors.tuna.tsinghua.edu.cn|g' /etc/apt/sources.list.d/debian.sources 2>/dev/null || true) \
    && apt-get update \
    && apt-get install -y --no-install-recommends libgl1 libglib2.0-0 libgomp1 \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt ./
ARG PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple
RUN pip install --no-cache-dir -i ${PIP_INDEX_URL} -r requirements.txt

COPY app ./app

RUN groupadd -r ocr && useradd -r -g ocr -u 10001 -s /usr/sbin/nologin ocr
USER 10001

EXPOSE 8091

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8091/health', timeout=3).read()"

CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8091"]
