# Build the Vue application.
FROM node:22-alpine AS build
WORKDIR /app

COPY package*.json ./
RUN npm install --force --no-audit --no-fund

# Vite 在构建期把 VITE_* 打进静态产物：批量导入 workflow 服务地址由 docker-compose
# 以 build args 注入（http://127.0.0.1:3000，浏览器从宿主机直连）；默认值保持本地开发行为。
ARG VITE_WORKFLOW_URL=http://localhost:3000
ENV VITE_WORKFLOW_URL=$VITE_WORKFLOW_URL

# 对象存储公开读取基址（storage-mq-plan 任务 A5）：生产构建默认空值 = 同源相对 /products，
# 由 nginx 反代到本地 MinIO；本地 dev 仍由 frontend/.env 注入 http://localhost:9000/products。
ARG VITE_STORAGE_PUBLIC_BASE=
ENV VITE_STORAGE_PUBLIC_BASE=$VITE_STORAGE_PUBLIC_BASE

COPY . .
RUN npm run build

# Serve static assets with Nginx.
FROM nginx:1.27-alpine
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
# backend IP 漂移保活守护（40-backend-dns-watch.sh）：backend 容器重建换 IP 后自动
# nginx -s reload，防止全 /api 长时间 502。必须显式 COPY——上一行的 COPY . . 只在
# build 阶段生效，运行阶段不会带上任何构建上下文文件。
COPY docker-entrypoint.d/ /docker-entrypoint.d/
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
    CMD wget --quiet --tries=1 --output-document=- http://127.0.0.1/ || exit 1
