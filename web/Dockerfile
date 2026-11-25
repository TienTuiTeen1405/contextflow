FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
COPY web/package.json web/package.json
RUN npm ci
COPY web web
ARG NEXT_PUBLIC_CORE_API_URL=http://localhost:8080
ENV NEXT_PUBLIC_CORE_API_URL=$NEXT_PUBLIC_CORE_API_URL
ENV NEXT_TELEMETRY_DISABLED=1
ENV NODE_OPTIONS=--max-old-space-size=768
RUN npm run check:web && npm run build:web

FROM node:24-alpine AS runtime
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 HOSTNAME=0.0.0.0 PORT=3000
WORKDIR /app
COPY --from=build --chown=node:node /app/web/.next/standalone ./
COPY --from=build --chown=node:node /app/web/.next/static ./web/.next/static
USER node
EXPOSE 3000
CMD ["node", "web/server.js"]
