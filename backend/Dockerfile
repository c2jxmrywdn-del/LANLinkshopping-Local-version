# Build the Spring Boot backend.
FROM maven:3.9.11-eclipse-temurin-17 AS build
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

# Run as an unprivileged user.
FROM eclipse-temurin:17-jre
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends wget \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system app \
    && useradd --system --gid app --home-dir /app --no-create-home app

COPY --from=build --chown=app:app /workspace/target/lanlink-shopping.jar /app/app.jar

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
