# ============ 构建阶段 ============
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /app

# 先复制 pom.xml，利用 Docker 缓存加速依赖下载
COPY pom.xml .
RUN mvn dependency:go-offline -B

# 复制源码并构建
COPY src ./src
RUN mvn clean package -DskipTests -B

# ============ 运行阶段 ============
FROM eclipse-temurin:17-jre

WORKDIR /app

# 安装 curl 用于健康检查，设置时区
RUN apt-get update && apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/* && \
    ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo "Asia/Shanghai" > /etc/timezone

# 从构建阶段复制 jar
COPY --from=builder /app/target/*.jar app.jar

# 创建上传目录和 APK 下载目录并设置权限
RUN mkdir -p /app/uploads /app/apk && chmod 777 /app/uploads /app/apk

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
