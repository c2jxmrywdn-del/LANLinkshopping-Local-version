# Build the Spring Boot backend.
FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /workspace

COPY pom.xml .
RUN mvn -B -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests clean package

# Run as an unprivileged user.
FROM eclipse-temurin:17-jre
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends wget \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system app \
    && useradd --system --gid app --home-dir /app --no-create-home app

COPY --from=build --chown=app:app /workspace/target/lanlink-shopping.jar /app/app.jar

# 上传目录（营业执照/完税凭证/特殊许可/头像）：容器以非 root 用户运行，
# 必须在镜像内预先创建并授权，否则运行期 mkdirs 失败导致上传接口 500。
RUN mkdir -p /app/uploads/licenses /app/uploads/tax-proofs /app/uploads/special-permits /app/uploads/avatars \
    && chown -R app:app /app/uploads

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8"
EXPOSE 8080
USER app

HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=5 \
    CMD wget --quiet --tries=1 --output-document=- http://127.0.0.1:8080/api/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
