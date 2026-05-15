FROM icr.io/appcafe/open-liberty:full-java21-openj9-ubi-minimal

ARG VERSION=0.1.0-SNAPSHOT

COPY --chown=1001:0 src/main/liberty/config/server.xml /config/server.xml
COPY --chown=1001:0 target/insurance-app.war /config/apps/insurance-app.war
COPY --chown=1001:0 target/postgresql/postgresql-*.jar /config/postgresql/

# Wire Liberty's mpTelemetry-1.1 to the SigNoz OTel collector on the shared
# insurance-net network. OTEL_SDK_DISABLED=false is the magic flag — Liberty
# defaults the feature to disabled, even when it's in the feature manager.
# These work as long as the container joins `insurance-net`; override at
# `podman run -e ...` to point at a different collector.
ENV OTEL_SDK_DISABLED=false \
    OTEL_SERVICE_NAME=insurance-app \
    OTEL_EXPORTER_OTLP_ENDPOINT=http://signoz-otel-collector:4317 \
    OTEL_EXPORTER_OTLP_PROTOCOL=grpc \
    OTEL_METRICS_EXPORTER=otlp \
    OTEL_TRACES_EXPORTER=otlp \
    OTEL_LOGS_EXPORTER=otlp

RUN configure.sh

EXPOSE 9080 9443
