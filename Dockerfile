# RD-Bot application image (single-machine self-hosting, see README.md).
#
# Multi-stage build: frontend admin SPA -> Spring Boot jar -> runtime.
# Host requirements: Docker Engine/Desktop with Compose v2 only; no JDK, Maven,
# Node or psql on the host. All base images are pinned to immutable tags.

ARG NODE_IMAGE=node:22.19.0-bookworm-slim
ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-21
ARG RUNTIME_IMAGE=eclipse-temurin:21-jre-jammy

# ---------------------------------------------------------------- frontend ----
FROM ${NODE_IMAGE} AS frontend-build
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
# Contract tests + typecheck + production build are the frontend gate; vite emits
# the bundle straight into the Spring Boot static resources of this build context.
RUN node --experimental-strip-types --test test/*.test.ts \
    && npm run typecheck \
    && npm run build

# ---------------------------------------------------------------- backend -----
FROM ${MAVEN_IMAGE} AS java-build
WORKDIR /src
# Copy the Maven object model first so dependency downloads form their own layer.
COPY pom.xml mvnw ./
COPY .mvn/ .mvn/
COPY rag/pom.xml rag/
COPY engine/pom.xml engine/
COPY exec/pom.xml exec/
COPY skill/pom.xml skill/
COPY bootstrap/pom.xml bootstrap/
# Wrapper needs an executable bit; dependency prefetch keeps the source layer reusable.
RUN chmod +x mvnw \
    && ./mvnw -B -q -pl bootstrap -am -DskipTests dependency:go-offline
COPY --from=frontend-build /src/bootstrap/src/main/resources/static/admin \
    bootstrap/src/main/resources/static/admin
COPY . .
RUN ./mvnw -B -pl bootstrap -am -DskipTests package

# ---------------------------------------------------------------- runtime -----
FROM ${RUNTIME_IMAGE} AS runtime

ARG TARGETARCH
ARG DOCKER_CLI_VERSION=28.3.3

# Operator toolchain used by the in-container host verifier when it replays target
# repository commands: bash/curl/git/jq/python3 baseline plus Node 22/npm (copied
# from the pinned node image) and the Docker CLI (static build). Languages beyond
# this baseline are the operator's responsibility (custom runtime profiles).
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        bash \
        ca-certificates \
        curl \
        git \
        jq \
        python3 \
        python3-pip \
        python3-venv \
        build-essential \
    && rm -rf /var/lib/apt/lists/*
COPY --from=frontend-build /usr/local/bin/node /usr/local/bin/node
COPY --from=frontend-build /usr/local/lib/node_modules /usr/local/lib/node_modules
RUN ln -s /usr/local/lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
    && ln -s /usr/local/lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx \
    && node --version && npm --version

RUN curl -fsSL --retry 3 \
        "https://download.docker.com/linux/static/stable/${TARGETARCH}/docker-${DOCKER_CLI_VERSION}.tgz" \
      | tar -xz -C /tmp \
    && install -m 0755 /tmp/docker/docker /usr/local/bin/docker \
    && rm -rf /tmp/docker \
    && docker --version

# OCI metadata. VCS_REF is supplied by the build (--build-arg VCS_REF=$(git rev-parse HEAD)).
ARG VCS_REF=unknown
LABEL org.opencontainers.image.source="https://github.com/wanghehe123/rd-bot" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.title="RD-Bot" \
      org.opencontainers.image.description="Auditable AI delivery harness: requirement in, verified PR out."

# Non-root runtime user; Docker socket access is granted at runtime by Compose via
# group_add with the host socket GID. No docker daemon ever runs inside the image.
RUN useradd --uid 10001 --user-group --create-home --shell /usr/sbin/nologin rdbot \
    && mkdir -p /app /work \
    && chown -R rdbot:rdbot /app /work
WORKDIR /app
COPY --from=java-build /src/bootstrap/target/bootstrap-0.1.0-SNAPSHOT.jar /app/app.jar

USER rdbot
EXPOSE 18080
# Read-only liveness probe: the admin SPA index must be served. No side-effect API.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=5 \
    CMD curl -fsS http://127.0.0.1:18080/admin -o /dev/null || exit 1
# exec-form entrypoint: the JVM is PID 1, runs a single foreground process and
# handles SIGTERM for graceful shutdown.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
