# 不用 `# syntax=docker/dockerfile:1` 固定前端镜像：本文件只用到 BuildKit 内置前端就支持的
# RUN --mount=type=cache（Dockerfile 1.2+，Docker Desktop/CI 的 buildx 默认即是），
# 少一个必须联网拉取的 docker/dockerfile 前端镜像，离线/受限网络下也能构建。
#
# e-aio 应用镜像（P0 册 3.8 阶段 7；P1-3 起改为**多阶段构建**）。
#
# 两个阶段：
#   builder —— 容器内用 Maven 打出可执行 jar（spring-boot-maven-plugin repackage）。
#              `~/.m2` 走 BuildKit cache mount：跨次构建复用依赖，不因源码变更重下整个仓库。
#   runtime —— 只带 JRE 21 + jar：非 root 用户、HEALTHCHECK 指向 Actuator 真实健康端点。
#
# 构建（仓库根目录，BuildKit；Docker Desktop 默认即 BuildKit）：
#   docker build -t eaio-it .
# 空应用运行（无库、无 Redis：基础配置已排除 DataSourceAutoConfiguration，见 application.yml）：
#   docker run -d --name eaio-it -p 18080:8080 eaio-it
#   curl http://localhost:18080/api/actuator/health
#
# 健康端点带 servlet context-path（`server.servlet.context-path=/api`），所以是 /api/actuator/health。
# 该响应被应用的 ApiResponseAdvice 包了一层统一返回体，状态在 data.status：
#   {"code":0,"message":"成功","data":{"groups":[...],"status":"UP"},"traceId":"..."}
# 健康检查因此只看 HTTP 状态码（UP=200 / DOWN=503），不看响应体形状。

# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build
# 整个后端多模块一次性拷入：Maven 反应堆需要父 POM + 全部子模块才能解析（源码变更不影响 .m2 缓存挂载）
COPY backend/e-aio/ /build/

# -DskipTests：镜像构建只负责产出 jar，测试与门禁的权威在 CI（阶段 1–5；发布门禁见 .github/workflows/release.yml）
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -DskipTests package

# ---------- 运行阶段 ----------
FROM eclipse-temurin:21-jre AS runtime

# curl 用于 HEALTHCHECK（eclipse-temurin:21-jre 基础镜像自带；若换成精简镜像需自行安装）
RUN groupadd --system --gid 1001 eaio \
    && useradd --system --uid 1001 --gid eaio --home-dir /app eaio

WORKDIR /app
COPY --from=builder --chown=eaio:eaio /build/e-aio-app/target/e-aio-app-*.jar /app/app.jar

# 空应用（无库、无 Redis）默认值：Redis 健康指示器在连不上 Redis 时会把整体健康压成 DOWN/503，
# 而本镜像的默认运行形态就是"无外部依赖的空应用"（上面 docker run 那条）。
# 接入了 Redis 的部署把它覆盖回 true，健康检查即恢复对 Redis 的探测：
#   docker run -e MANAGEMENT_HEALTH_REDIS_ENABLED=true ...
# 数据库不需要同类开关：DataSourceAutoConfiguration 已排除，无数据源就没有 db 健康指示器。
ENV MANAGEMENT_HEALTH_REDIS_ENABLED=false

USER eaio
EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
    CMD curl -fsS http://localhost:8080/api/actuator/health || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
