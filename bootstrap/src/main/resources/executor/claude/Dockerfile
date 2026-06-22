ARG NODE_BASE_IMAGE=node:22-bookworm-slim
FROM ${NODE_BASE_IMAGE}

ARG CLAUDE_CODE_VERSION

RUN echo "${CLAUDE_CODE_VERSION}" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' \
    || (echo "CLAUDE_CODE_VERSION must be an exact semver version, not latest or a range" >&2; exit 64)

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        git \
        jq \
        openssh-client \
        sudo \
    && rm -rf /var/lib/apt/lists/*

RUN npm install -g "@anthropic-ai/claude-code@${CLAUDE_CODE_VERSION}" \
    && npm cache clean --force

RUN groupadd --system rdbot \
    && useradd --system --gid rdbot --create-home --home-dir /home/rdbot --shell /bin/bash rdbot \
    && mkdir -p /work/input /work/repo /work/output \
    && printf '%s\n' '{"bypassPermissionsModeAccepted":true}' > /home/rdbot/.claude.json \
    && printf '%s\n' 'rdbot ALL=(root) NOPASSWD: /usr/bin/apt-get, /usr/bin/apt' > /etc/sudoers.d/rdbot-toolchain \
    && chmod 0440 /etc/sudoers.d/rdbot-toolchain \
    && chown -R rdbot:rdbot /home/rdbot /work

COPY rd-claude-entrypoint.sh /usr/local/bin/rd-claude-entrypoint
RUN chmod 0755 /usr/local/bin/rd-claude-entrypoint

USER rdbot
WORKDIR /work/repo
ENTRYPOINT ["rd-claude-entrypoint"]
