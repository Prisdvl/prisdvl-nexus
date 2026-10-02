# ============================================================
# prisdvl-nexus 后端**运行**镜像（Phase 5.7）
#
# 【为什么是"只运行"，不是多阶段构建】
# 多阶段（maven:3.9-eclipse-temurin-21 里跑 mvn package）看着更"自包含"，
# 但在本仓库是错的：
#   1. 镜像里要重新下载整棵依赖树（本机 ~/.m2 已经 1GB+），构建 5~10 分钟；
#   2. 本项目的构建约定本来就是"宿主机构建、容器只运行"——docs/deployment.md §6
#      写明打包前必须先杀掉 java 进程，否则 Windows 上 jar 文件被锁；
#   3. 需求明确"不要在镜像里使用开发环境"。构建工具链（maven、源码、.m2）
#      放进运行镜像就是开发环境，只为了省一次 `mvn package`。
#
# 因此前置条件是**显式**的（compose 不会替你满足）：
#   JAVA_HOME=<jdk-21> mvn -B package -DskipTests   → nexus-app/target/nexus-app-*.jar
# 构建上下文 = 仓库根（见仓库根的 .dockerignore）。
#
# 【镜像里是什么】
#   Temurin 21 JRE（不是 JDK：运行期不需要编译器）+ fat jar + curl（仅供健康检查）
# ============================================================
FROM eclipse-temurin:21-jre-jammy

# curl 只用于 compose 的健康检查（打 /actuator/health）。
# 不装 wget 是因为 curl 的 `-f` 语义更直接：非 2xx/3xx 即非零退出。
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# 非 root 运行：容器被打穿后的第一道边界（业务代码里没有一条权限依赖 root）
RUN useradd --system --uid 1001 --create-home nexus

COPY --chown=nexus:nexus nexus-app/target/nexus-app-*.jar /app/app.jar

# 原始文件存储根目录（NEXUS_STORAGE_ROOT=/app/data，见 application.yml）。
# 这里建目录只是为了让**不挂卷**时也能起来；生产必须由 compose 挂卷覆盖，
# 否则 docker compose down 会把上传的原始文件一起带走。
RUN mkdir -p /app/data && chown -R nexus:nexus /app

USER nexus
EXPOSE 8080

# MaxRAMPercentage 而不是写死 -Xmx：容器内存上限由 compose 决定，
# 固定 -Xmx 会在"改小容器上限"时让 JVM 顶着宿主机内存算堆，越界被 OOMKill。
# ExitOnOutOfMemoryError：OOM 时立刻退出让 Docker 按 restart 策略重启，
# 而不是留一个堆已坏、还在对外提供 500 的进程。
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
