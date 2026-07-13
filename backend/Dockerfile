# Build stage
FROM golang:1.26-alpine AS build
WORKDIR /src
RUN apk add --no-cache git ca-certificates
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -o /out/routebot-api ./cmd/api

# Runtime
FROM alpine:3.21
RUN apk add --no-cache ca-certificates tzdata wget
WORKDIR /app
COPY --from=build /out/routebot-api /app/routebot-api
COPY migrations /app/migrations
ENV MIGRATIONS_DIR=/app/migrations
ENV MEDIA_STORAGE_PATH=/data/media
EXPOSE 8080
USER nobody
ENTRYPOINT ["/app/routebot-api"]
