# Host에서 `npm run build`(nuxi generate)로 .output/public 을 만든 뒤 이 이미지만 빌드한다.
# Node 빌드를 이미지 안에서 돌리지 않는다. EC2에서는 docker load 만 한다.
FROM nginx:1.27-alpine
WORKDIR /usr/share/nginx/html

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY .output/public ./

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
