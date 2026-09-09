# ─────────────────────────────────────────────
#  Multi-stage build – S3 OneDrive Gateway
# ─────────────────────────────────────────────

# Stage 1 – Build
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app

# Cache dependencies first
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Build application
COPY src ./src
RUN mvn package -DskipTests -q

# ─────────────────────────────────────────────
# Stage 2 – Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Non-root user for security
RUN addgroup -S gateway && adduser -S gateway -G gateway

# Copy fat jar
COPY --from=build /app/target/s3-onedrive-gateway-*.jar app.jar

# Temp dir for multipart uploads
RUN mkdir -p /tmp/s3-gateway-multipart && chown gateway:gateway /tmp/s3-gateway-multipart

USER gateway

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD wget -qO- http://localhost:8080/health || exit 1

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
