FROM icr.io/appcafe/open-liberty:kernel-slim-java21-openj9-ubi-minimal

ARG VERSION=0.1.0-SNAPSHOT

COPY --chown=1001:0 src/main/liberty/config/server.xml /config/server.xml
COPY --chown=1001:0 target/insurance-app.war /config/apps/insurance-app.war

RUN features.sh
RUN configure.sh

EXPOSE 9080 9443
