ARG NODE_BASE_IMAGE=node:22.19.0-bookworm-slim
FROM ${NODE_BASE_IMAGE}

# Mirror override for restricted-network builds; versions stay pinned by package-lock.json.
ARG NPM_REGISTRY=https://registry.npmjs.org
# Optional Debian mirror override (e.g. http://mirrors.aliyun.com/debian) for
# restricted-network builds; default keeps the official mirror.
ARG DEBIAN_MIRROR=deb.debian.org

ENV NODE_ENV=production
WORKDIR /opt/rd-pi-bridge

# Repo toolchain parity with the Claude executor image: agents run tests with
# python3/git inside the container; pytz+sqlparse are required by Django suites.
# build-essential keeps node-gyp fallbacks working for native modules when
# prebuilt binary downloads fail (flaky GitHub releases on restricted networks).
RUN sed -i "s|deb.debian.org|${DEBIAN_MIRROR}|g" /etc/apt/sources.list.d/debian.sources 2>/dev/null || true \
    && apt-get update \
    && apt-get upgrade -y \
    && apt-get install -y --no-install-recommends \
        build-essential \
        ca-certificates \
        git \
        jq \
        python3 \
        python3-pip \
        python3-venv \
    && rm -rf /var/lib/apt/lists/* \
    && python3 -m pip install --break-system-packages --no-cache-dir pytz sqlparse

COPY package.json package-lock.json ./
RUN npm ci --omit=dev --registry=${NPM_REGISTRY} && npm cache clean --force
COPY src ./src
COPY protocol ./protocol

RUN mkdir -p /work/input /work/output /work/cache /work/pi-agent /ms-playwright \
    && chown -R node:node /opt/rd-pi-bridge /work /ms-playwright

USER node
ENTRYPOINT ["node", "/opt/rd-pi-bridge/src/rd-pi-bridge.mjs"]
