# 商品批量导入 workflow 服务（独立 Next.js 子应用，见 package.json：next start -p 3000）
FROM node:20-alpine
WORKDIR /app

COPY package*.json ./
RUN npm ci --no-audit --no-fund

COPY . .
RUN npm run build

# workflow 本地运行时把可恢复事件日志写入该目录（默认 .workflow-data 相对 CWD），
# docker-compose 用命名卷挂载它：容器重建后导入进度可从断点继续。
ENV WORKFLOW_LOCAL_DATA_DIR=/app/.workflow-data

EXPOSE 3000
CMD ["npm", "start"]
