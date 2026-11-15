# LANLink ASR Service

本地兜底语音识别 / 视频抽帧服务：FastAPI + faster-whisper（medium, `device=cpu`,
`compute_type=int8`）+ ffmpeg。不依赖 torch（faster-whisper 走 CTranslate2）。

## 资源要求

- **容器内存建议 6GB**（medium int8 模型常驻约 1.5–2GB，加上 ffmpeg 解码与 30s 视频
  抽帧峰值；`mem_limit: 6g` 较为稳妥，低于 3GB 时长音频/视频可能被 OOM Kill）。
- 模型体积约 1.5GB，首次 `/transcribe` 时从 HuggingFace 下载到 `ASR_MODEL_DIR`
  （默认 `/app/models`）。**生产环境请把该目录挂载为持久卷**，否则重建容器会重新下载。
  离线环境可提前把模型目录拷贝进卷，并设置 `HF_HUB_OFFLINE=1`。

## 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/health` | `{"status":"ok","model":"medium","engine":"faster-whisper"}` |
| POST | `/transcribe` | `{audioBase64, mime?, language?: "zh"\|"en"\|"auto"}` |
| POST | `/frames` | `{videoBase64, fps?, maxFrames?, maxSeconds?}` |

`/transcribe` 返回 `{text, segments:[{startMs,endMs,text}], confidence?, engine, elapsedMs}`。
`/frames` 返回 `{frames:[{index,timeMs,imageBase64}], audioBase64?, durationMs, truncated}`，
帧为 jpg（长边 ≤1280），音轨为 16k mono wav。

## 限制

- 音频：base64 解码后 ≤10MB，时长 ≤60s；超限返回 `413`（`AUDIO_TOO_LARGE` / `AUDIO_TOO_LONG`）。
- 视频：base64 解码后 ≤20MB，处理时长 ≤30s；超时长按 `maxSeconds`（上限 30s）硬截断，
  并在响应里置 `truncated=true`；超体积返回 `413`（`VIDEO_TOO_LARGE`）。

## 并发与超时

- 模型懒加载 + 全局锁：并发首请求只加载一次；推理串行（CPU 打满时更稳）。
- 模型未就绪时首个请求需要等待下载，网关侧超时 60s；建议部署后先跑一次短音频预热。

## 环境变量

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `ASR_MODEL` | `medium` | faster-whisper 模型名 |
| `ASR_MODEL_DIR` | `/app/models` | 模型下载/缓存目录 |
| `HF_HOME` | `/app/models` | HuggingFace 缓存目录 |
| `LOG_LEVEL` | `INFO` | 日志级别 |

## 本地构建

```bash
docker build -t lanlink-asr-service:latest ./asr-service
docker run --rm -p 8092:8092 -v asr-models:/app/models --memory=6g lanlink-asr-service:latest
```
