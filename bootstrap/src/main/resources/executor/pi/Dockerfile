ARG NODE_BASE_IMAGE=node:22.19.0-bookworm-slim
FROM ${NODE_BASE_IMAGE}

# Mirror override for restricted-network builds; versions stay pinned by package-lock.json.
ARG NPM_REGISTRY=https://registry.npmjs.org

ENV NODE_ENV=production
WORKDIR /opt/rd-pi-bridge

# Repo toolchain parity with the Claude executor image: agents run tests with
# python3/git inside the container; pytz+sqlparse are required by Django suites.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
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

RUN mkdir -p /work/input /work/output /work/cache /work/pi-agent \
    && chown -R node:node /opt/rd-pi-bridge /work

USER node
ENTRYPOINT ["node", "/opt/rd-pi-bridge/src/rd-pi-bridge.mjs"]
