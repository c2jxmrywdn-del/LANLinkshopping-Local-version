# Build the Spring Boot backend.
#
# 基础镜像名可在 CI/受限网络中覆盖：默认仍是 Docker Hub 官方 tag，不改变现有
# `docker compose build backend` 的完整 Maven 构建语义。
ARG MAVEN_IMAGE=maven:3.9.11-eclipse-temurin-17
ARG JRE_IMAGE=eclipse-temurin:17-jre

FROM ${MAVEN_IMAGE:-maven:3.9.11-eclipse-temurin-17} AS build
WORKDIR /workspace

# Maven 镜像源：容器内直连 repo.maven.apache.org 会被宿主代理 fake-IP DNS 污染
# （198.18.x.x Connect timed out，2026-11-05 实证）——统一走阿里云公共仓库。
RUN mkdir -p /root/.m2 && printf '%s\n' \
    '<?xml version="1.0" encoding="UTF-8"?>' \
    '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">' \
    '  <mirrors>' \
    '    <mirror>' \
    '      <id>aliyun-public</id>' \
    '      <mirrorOf>central</mirrorOf>' \
    '      <url>https://maven.aliyun.com/repository/public</url>' \
    '    </mirror>' \
    '  </mirrors>' \
    '</settings>' > /root/.m2/settings.xml

COPY pom.xml .
RUN mvn -B -DskipTests dependency:go-offline
COPY src ./src
# maven.test.skip=true：镜像构建不编译测试源码（skipTests 仍会 testCompile，
# 任何在途测试文件破损都会阻塞发版镜像，与运行时无关）
RUN mvn -B -Dmaven.test.skip=true clean package

# Runtime base shared by the full build and the prebuilt-jar fast path.
FROM ${JRE_IMAGE:-eclipse-temurin:17-jre} AS runtime-base
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends wget fontconfig fonts-wqy-zenhei \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system app \
    && useradd --system --gid app --home-dir /app --no-create-home app

# 上传目录（营业执照/完税凭证/特殊许可/头像/聊天媒体）：容器以非 root 用户运行，
# 必须在镜像内预先创建并授权，否则运行期 mkdirs 失败导致上传接口 500。
RUN mkdir -p /app/uploads/licenses /app/uploads/tax-proofs /app/uploads/special-permits /app/uploads/avatars /app/uploads/chat \
    && chown -R app:app /app/uploads

# 时区统一：容器基础镜像默认 UTC，JVM 与业务日期（签到/活动时间窗）统一为北京时间；
# 代码侧 TimeZone.setDefault 亦已兜底，此处显式固化避免依赖启动类执行顺序。
ENV TZ=Asia/Shanghai
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"
EXPOSE 8080
USER app

HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=5 \
    CMD wget --quiet --tries=1 --output-document=- http://127.0.0.1:8080/api/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

# 本地离线快速路径：直接打包宿主已构建的 jar，不解析 build stage（因此不需要
# Maven 基础镜像或容器内 Maven 仓库）。Compose 仅在 BACKEND_JAR_PREBUILT 非空时
# 显式指定 --target runtime-prebuilt。
FROM runtime-base AS runtime-prebuilt
ARG JAR_FILE=target/lanlink-shopping.jar
COPY --chown=app:app ${JAR_FILE} /app/app.jar
RUN test -s /app/app.jar

# 默认最后 stage 保持完整 Maven 构建；CI/普通环境不传 target 时走的仍是这条路径。
FROM runtime-base AS runtime
COPY --from=build --chown=app:app /workspace/target/lanlink-shopping.jar /app/app.jar
