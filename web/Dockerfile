FROM node:24-alpine AS dependencies
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

FROM node:24-alpine AS build
WORKDIR /app
ENV NEXT_TELEMETRY_DISABLED=1
COPY --from=dependencies /app/node_modules ./node_modules
COPY . .
ARG NEXT_PUBLIC_API_BASE_URL
ARG NEXT_PUBLIC_API_MOCK=false
ARG NEXT_PUBLIC_ENABLE_DEV_LOGIN=false
ENV NEXT_PUBLIC_API_BASE_URL=${NEXT_PUBLIC_API_BASE_URL} \
    NEXT_PUBLIC_API_MOCK=${NEXT_PUBLIC_API_MOCK} \
    NEXT_PUBLIC_ENABLE_DEV_LOGIN=${NEXT_PUBLIC_ENABLE_DEV_LOGIN}
RUN test -n "$NEXT_PUBLIC_API_BASE_URL" && mkdir -p public && npm run build

FROM node:24-alpine AS runtime
WORKDIR /app
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 PORT=3000 HOSTNAME=0.0.0.0
RUN addgroup -S -g 10001 pyeonit && adduser -S -u 10001 -G pyeonit pyeonit
COPY --from=build --chown=10001:10001 /app/.next/standalone ./
COPY --from=build --chown=10001:10001 /app/.next/static ./.next/static
COPY --from=build --chown=10001:10001 /app/public ./public
USER 10001:10001
EXPOSE 3000
CMD ["node", "server.js"]
