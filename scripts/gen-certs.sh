#!/usr/bin/env bash
# Generate the demo mTLS chain for slice 10 (partner API).
# Output: compose/certs/{ca.crt, ca.key, server.crt, server.key,
#                       client.crt, client.key, client.p12, mi-truststore.jks,
#                       mi-keystore.jks}
#
# Self-signed, demo-only, regenerable. NEVER commit the .crt/.key/.jks files —
# compose/certs/.gitignore excludes them so a fresh clone forces a re-run.
#
# The MI integration consumes:
#   mi-keystore.jks   — holds the client cert+key MI presents to partner-mock
#   mi-truststore.jks — holds the CA cert MI uses to verify partner-mock's server cert
# Both with password "wso2carbon" so they slot into MI's default keystore
# password convention without deployment.toml edits.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.."; pwd)"
CERTS="$ROOT/compose/certs"
mkdir -p "$CERTS"
cd "$CERTS"

KEYSTORE_PASS="wso2carbon"

echo "==> generating CA"
openssl genrsa -out ca.key 2048 2>/dev/null
openssl req -x509 -new -key ca.key -days 3650 -subj "/CN=insurance-demo-ca" -out ca.crt 2>/dev/null

echo "==> generating server cert (CN=partner-mock)"
openssl genrsa -out server.key 2048 2>/dev/null
openssl req -new -key server.key -subj "/CN=partner-mock" -out server.csr 2>/dev/null
cat > server.ext <<EOF
subjectAltName = DNS:partner-mock, DNS:localhost
EOF
openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
    -days 3650 -out server.crt -extfile server.ext 2>/dev/null

echo "==> generating client cert (CN=insurance-mi-client)"
openssl genrsa -out client.key 2048 2>/dev/null
openssl req -new -key client.key -subj "/CN=insurance-mi-client" -out client.csr 2>/dev/null
openssl x509 -req -in client.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
    -days 3650 -out client.crt 2>/dev/null

echo "==> packaging client cert as PKCS#12 (for keytool import)"
openssl pkcs12 -export \
    -in client.crt -inkey client.key -name insurance-mi-client \
    -out client.p12 -passout pass:$KEYSTORE_PASS 2>/dev/null

echo "==> building MI keystore (client cert/key MI presents to partner)"
rm -f mi-keystore.jks
keytool -importkeystore -srckeystore client.p12 -srcstoretype PKCS12 \
    -srcstorepass $KEYSTORE_PASS \
    -destkeystore mi-keystore.jks -deststoretype JKS \
    -deststorepass $KEYSTORE_PASS -destkeypass $KEYSTORE_PASS \
    -noprompt 2>/dev/null

echo "==> building MI truststore (CA cert for partner-mock verification)"
rm -f mi-truststore.jks
keytool -importcert -keystore mi-truststore.jks -storetype JKS \
    -storepass $KEYSTORE_PASS -alias insurance-demo-ca \
    -file ca.crt -noprompt 2>/dev/null

rm -f server.csr client.csr server.ext ca.srl

echo "==> done. Generated:"
ls -la "$CERTS"
