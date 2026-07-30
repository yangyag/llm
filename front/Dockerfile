FROM node:22-bookworm-slim AS frontend-builder
WORKDIR /app

COPY package*.json ./
RUN npm ci

COPY . ./

ARG NUXT_PUBLIC_API_BASE=
ENV NUXT_PUBLIC_API_BASE=${NUXT_PUBLIC_API_BASE}

RUN npm run build

FROM nginx:1.27-alpine
WORKDIR /usr/share/nginx/html

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=frontend-builder /app/.output/public ./

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
