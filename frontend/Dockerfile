# Build the Vue application.
FROM node:22-alpine AS build
WORKDIR /app

COPY package*.json ./
RUN npm install --force --no-audit --no-fund

# Vite 在构建期把 VITE_* 打进静态产物：批量导入 workflow 服务地址由 docker-compose
# 以 build args 注入（http://127.0.0.1:3000，浏览器从宿主机直连）；默认值保持本地开发行为。
ARG VITE_WORKFLOW_URL=http://localhost:3000
ENV VITE_WORKFLOW_URL=$VITE_WORKFLOW_URL

COPY . .
RUN npm run build

# Serve static assets with Nginx.
FROM nginx:1.27-alpine
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
    CMD wget --quiet --tries=1 --output-document=- http://127.0.0.1/ || exit 1
