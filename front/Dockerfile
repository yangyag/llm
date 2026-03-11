FROM node:22-bookworm-slim AS frontend-builder
WORKDIR /app

COPY package*.json ./
RUN npm ci

COPY . ./

ARG VITE_API_BASE_URL=
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}

RUN npm run build

FROM nginx:1.27-alpine
WORKDIR /usr/share/nginx/html

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=frontend-builder /app/dist ./

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
