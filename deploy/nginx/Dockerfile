FROM nginx:1.27-alpine

RUN apk add --no-cache openssl

COPY nginx.conf /etc/nginx/nginx.conf
COPY proxy_params.conf /etc/nginx/conf.d/proxy_params.conf
COPY docker-entrypoint.sh /docker-entrypoint-routebot.sh
RUN chmod +x /docker-entrypoint-routebot.sh

EXPOSE 80 443
ENTRYPOINT ["/docker-entrypoint-routebot.sh"]
