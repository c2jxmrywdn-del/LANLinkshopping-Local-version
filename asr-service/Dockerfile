# LANLink ASR / 视频抽帧服务：faster-whisper(medium, int8, cpu) + ffmpeg。
# 模型首次使用从 HuggingFace 下载到 /app/models（可用 ASR_MODEL_DIR 覆盖，建议挂卷持久化）。
# 基础镜像走国内镜像源：Docker Hub 直连在当前环境不可达
ARG BASE_IMAGE=docker.m.daocloud.io/library/python:3.11-slim
FROM ${BASE_IMAGE}

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    HF_HOME=/app/models \
    ASR_MODEL_DIR=/app/models \
    ASR_MODEL=medium \
    LOG_LEVEL=INFO

WORKDIR /app

# ffmpeg/ffprobe 抽帧抽音轨；libgomp1 为 CTranslate2(OpenMP) 运行时依赖。
RUN (sed -i 's|deb.debian.org|mirrors.tuna.tsinghua.edu.cn|g' /etc/apt/sources.list.d/debian.sources 2>/dev/null || true) \
    && apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg libgomp1 \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt ./
ARG PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple
RUN pip install --no-cache-dir -i ${PIP_INDEX_URL} -r requirements.txt

COPY app ./app

RUN groupadd -r asr && useradd -r -g asr -u 10001 -s /usr/sbin/nologin asr \
    && mkdir -p /app/models && chown -R 10001:asr /app/models
USER 10001

EXPOSE 8092

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8092/health', timeout=3).read()"

CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8092"]
