# Build the Vue application.
FROM node:25-alpine AS build
WORKDIR /app

COPY package*.json ./
RUN npm install --force --no-audit --no-fund
COPY . .
RUN npm run build

# Serve static assets with Nginx.
FROM nginx:1.27-alpine
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
    CMD wget --quiet --tries=1 --output-document=- http://127.0.0.1/ || exit 1
