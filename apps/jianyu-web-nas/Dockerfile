# Jianyu NAS web runtime.
#
# The build context is the repository root because the browser client imports
# the shared engine packages by relative path. The image contains no package
# manager, no npm dependencies, and no build step.

FROM node:22-alpine@sha256:0a7108bf6c7bf5de370ffb1a3ed6be93d405b43ff159f681a8d18c0e2bc2e402

WORKDIR /app

COPY apps/jianyu-web-nas ./apps/jianyu-web-nas
COPY packages/foe-core/src ./packages/foe-core/src
COPY packages/foe-opportunity/src ./packages/foe-opportunity/src
COPY packages/foe-schema/src ./packages/foe-schema/src
COPY packages/foe-vault/src ./packages/foe-vault/src
COPY packages/pack-sdk/src ./packages/pack-sdk/src
COPY packages/policy-sdk/src ./packages/policy-sdk/src
COPY packages/provider-sdk/src ./packages/provider-sdk/src
COPY packages/brand-config ./packages/brand-config
COPY examples ./examples

ENV JIANYU_DATA_DIR=/data \
    JIANYU_HOST=0.0.0.0 \
    JIANYU_PORT=8080

VOLUME ["/data"]

# node:22-alpine ships an unprivileged account; the server never needs root.
USER node

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
  CMD node -e "fetch('http://127.0.0.1:' + (process.env.JIANYU_PORT || 8080) + '/healthz').then((response) => process.exit(response.ok ? 0 : 1)).catch(() => process.exit(1))"

CMD ["node", "apps/jianyu-web-nas/server/server.mjs"]
