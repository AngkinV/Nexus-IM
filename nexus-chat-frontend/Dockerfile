# ============ 构建阶段 ============
FROM node:20-alpine AS builder

WORKDIR /app

# 复制 package.json 并安装依赖
COPY package*.json ./
RUN npm ci

# 复制源码
COPY . .

# 构建参数
ARG VITE_API_BASE_URL=/api
ARG VITE_WS_URL=wss://chat.angkin.cn/ws

# 设置环境变量并构建
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}
ENV VITE_WS_URL=${VITE_WS_URL}

RUN npm run build

# ============ 运行阶段 ============
FROM nginx:alpine

# 设置时区
RUN apk add --no-cache tzdata && \
    cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo "Asia/Shanghai" > /etc/timezone

# 复制构建产物
COPY --from=builder /app/dist /usr/share/nginx/html

# 创建上传文件挂载点
RUN mkdir -p /app/uploads

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
