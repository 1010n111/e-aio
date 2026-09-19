# e-aio 应用镜像（P0 册 3.8 阶段 7）
#
# 单阶段构建 + 直接拷贝已打好的 boot jar：构建由 Maven 负责（CI 阶段 1–5 已跑完门禁），
# 镜像只做运行。这样镜像构建不重复拉依赖、不需要在镜像里跑 Maven，产物可复现。
#
# 注意：本文件在**无 Docker 的机器上无法验证**；镜像构建属 P0 交付物但推送到 registry 属 P1（3.8 阶段 7）。
FROM eclipse-temurin:21-jre

# 非 root 运行
RUN groupadd --system --gid 1001 eaio \
    && useradd --system --uid 1001 --gid eaio --home-dir /app eaio

WORKDIR /app
COPY --chown=eaio:eaio backend/e-aio/e-aio-app/target/e-aio-app-*.jar /app/app.jar

USER eaio
EXPOSE 8080

# 健康检查用 Actuator（P0 册 3.5）；无库/无 Redis 时应用仍能起来（基础配置不连外部依赖）
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 \
    CMD ["java", "-version"]

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
