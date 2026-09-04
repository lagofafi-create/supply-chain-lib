#!/bin/sh
# install-certs.sh, inject internal CA cert(s) into the OS trust store AND the
# runtime-specific trust store. Runs BEFORE harden.sh (which removes the tools
# below). Runtime-aware because Java and Python do not use the OS store by default.
#
# Usage: install-certs.sh [--runtime auto|python|java|dotnet|none] <cert-name> [cert-name...]
# Certs are read from /tmp/pki/*.crt (copied in by the Dockerfile). A file may contain
# MULTIPLE PEM certs (root + intermediate bundle), it is split so every cert lands in
# every store (keytool/openssl-hashdir only take the first cert of a file otherwise).
set -eu

RUNTIME=auto
while [ "${1:-}" = "--runtime" ]; do RUNTIME="$2"; shift 2; done

CERT_DIR=/tmp/pki
# keytool alias prefix, configured in config/defaults.yaml (certAliasPrefix), passed by the
# rendered Dockerfile as an env var; alias = <prefix>-<n>
ALIAS_PREFIX="${CERT_ALIAS_PREFIX:-acme-internal}"
log() { echo "[certs] $*"; }
log "installing internal CAs (runtime=$RUNTIME)"

ls "$CERT_DIR"/*.crt >/dev/null 2>&1 || { log "no certs to install"; exit 0; }

# 0. split bundles -------------------------------------------------------
# A .crt file may be a BUNDLE (e.g. root + intermediate in one PEM). keytool imports
# only the FIRST cert of a file, and openssl's hashed dir indexes one per file, so
# split every file into single-cert parts and install THOSE everywhere. Pure POSIX sh
# (no awk: not guaranteed on minimal bases).
SPLIT_DIR=/tmp/pki-split
rm -rf "$SPLIT_DIR"; mkdir -p "$SPLIT_DIR"
for c in "$CERT_DIR"/*.crt; do
  base="$(basename "$c" .crt)"; n=0; f=""
  while IFS= read -r line; do
    case "$line" in *"-----BEGIN CERTIFICATE-----"*) n=$((n + 1)); f="$SPLIT_DIR/$base-$n.crt"; : > "$f" ;; esac
    [ -n "$f" ] && printf '%s\n' "$line" >> "$f"
    case "$line" in *"-----END CERTIFICATE-----"*) f="" ;; esac
  done < "$c"
done
ls "$SPLIT_DIR"/*.crt >/dev/null 2>&1 || { echo "[certs] ERROR: no PEM certificates found in $CERT_DIR/*.crt" >&2; exit 1; }
log "$(ls "$SPLIT_DIR"/*.crt | wc -l | tr -d ' ') certificate(s) found in $(ls "$CERT_DIR"/*.crt | wc -l | tr -d ' ') file(s)"

# 1. OS trust store ------------------------------------------------------
OS_OK=0
if command -v update-ca-certificates >/dev/null 2>&1; then          # Debian/Ubuntu
  mkdir -p /usr/local/share/ca-certificates
  for c in "$SPLIT_DIR"/*.crt; do cp "$c" "/usr/local/share/ca-certificates/$(basename "$c")"; done
  update-ca-certificates && OS_OK=1
elif command -v update-ca-trust >/dev/null 2>&1; then               # RHEL/UBI
  mkdir -p /etc/pki/ca-trust/source/anchors
  for c in "$SPLIT_DIR"/*.crt; do cp "$c" "/etc/pki/ca-trust/source/anchors/$(basename "$c")"; done
  update-ca-trust extract && OS_OK=1
else                                                                # micro: no update tool
  for bundle in /etc/ssl/certs/ca-certificates.crt /etc/pki/tls/certs/ca-bundle.crt; do
    # if/fi, not `[ -f ] &&`: a missing LAST bundle would end the loop non-zero and,
    # under set -e, kill the script before the OS_OK check
    if [ -f "$bundle" ]; then cat "$SPLIT_DIR"/*.crt >> "$bundle" && OS_OK=1; fi
  done
fi
# A security pipeline must NOT ship an image where CA injection silently failed.
if [ "$OS_OK" != 1 ]; then
  echo "[certs] ERROR: no OS trust store found, add 'ca-certificates' to this variant's packages" >&2
  exit 1
fi

# 2. runtime-specific trust store ---------------------------------------
# auto: look at what the image ships rather than being told
if [ "$RUNTIME" = auto ]; then
  if command -v keytool >/dev/null 2>&1 || [ -f "${JAVA_HOME:-/nonexistent}/lib/security/cacerts" ]; then RUNTIME=java
  elif command -v python3 >/dev/null 2>&1; then RUNTIME=python
  else RUNTIME=none; fi
  log "runtime detected: $RUNTIME"
fi
case "$RUNTIME" in
  java)
    KS="${JAVA_HOME:-}/lib/security/cacerts"
    if [ ! -f "$KS" ] && command -v java >/dev/null 2>&1; then
      JH="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
      KS="$JH/lib/security/cacerts"
    fi
    if ! command -v keytool >/dev/null 2>&1 || [ ! -f "$KS" ]; then
      echo "[certs] ERROR: java runtime selected but keytool/cacerts not found (KS=$KS)" >&2
      exit 1
    fi
    i=0
    for c in "$SPLIT_DIR"/*.crt; do    # split parts: every cert of a bundle gets imported
      keytool -importcert -noprompt -trustcacerts \
        -alias "$ALIAS_PREFIX-$i" -file "$c" \
        -keystore "$KS" -storepass changeit \
        || { echo "[certs] ERROR: keytool import failed for $c into $KS" >&2; exit 1; }
      i=$((i + 1))
    done
    log "imported $i certificate(s) into JVM cacerts: $KS"
    ;;
  python)
    # CPython uses OpenSSL (OS store, already covered). pip/requests may use certifi:
    if command -v python3 >/dev/null 2>&1; then
      CERTIFI="$(python3 -c 'import certifi,sys; sys.stdout.write(certifi.where())' 2>/dev/null || true)"
      [ -n "$CERTIFI" ] && [ -f "$CERTIFI" ] && cat "$SPLIT_DIR"/*.crt >> "$CERTIFI" && \
        log "appended to certifi bundle: $CERTIFI" || true
    fi
    ;;
  dotnet)
    # .NET on Linux uses the OpenSSL/OS trust store, covered by step 1. Nothing extra.
    log ".NET uses OS trust store, no extra step"
    ;;
  *) : ;;
esac

rm -rf "$SPLIT_DIR"
log "done"
