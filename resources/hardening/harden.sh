#!/bin/sh
# harden.sh uniform hardening applied to every prod-eligible image.
#
# Design: this is the SAME script for ubuntu(apt) and ubi(dnf/microdnf). It is
# idempotent and only removes what actually exists, so on already-minimal bases
# (ubi-micro, chiselled ubuntu) the "strip" phase is effectively a no-op while
# the rest (setuid drop, cache/doc removal, self-cleanup) still runs.
#
# MUST be the LAST RUN in the image: it removes the package manager, shells and
# (step 7) all unnecessary userland binaries, so nothing after it can execute
# commands. That is by design a hardened prod base is consumed as a final
# stage (COPY + ENTRYPOINT), never with RUN. Triage happens via the debug/*
# toolbox images, not tools baked into prod bases.
set -eu

log() { echo "[harden] $*"; }
rm_bin() { p="$(command -v "$1" 2>/dev/null || true)"; [ -n "$p" ] && rm -f "$p" || true; }

log "start"

# 0. collect the "unnecessary userland binaries" strip list NOW (the query tools
#    are deleted in step 1); the list itself is removed in ONE rm invocation as
#    the very LAST command of this script (step 7).
#    Escape hatch: HARDEN_KEEP="tar gzip" (env, e.g. from a spec's common.env)
#    preserves the listed basenames.
STRIP_PKGS_DEB="coreutils findutils grep sed mawk tar gzip bzip2 xz-utils diffutils \
util-linux bsdutils debianutils hostname ncurses-bin login passwd mount procps \
perl-base sysvinit-utils e2fsprogs"
STRIP_PKGS_RPM="coreutils coreutils-single findutils grep sed gawk tar gzip xz \
diffutils util-linux util-linux-core procps-ng shadow-utils hostname ncurses vim-minimal"

in_keep() {
  b="${1##*/}"
  for k in ${HARDEN_KEEP:-}; do [ "$k" = "$b" ] && return 0; done
  return 1
}
STRIP_LIST=""
collect() {   # binaries only: bin dirs, never libs; never the non-login shell
  case "$1" in /bin/*|/sbin/*|/usr/bin/*|/usr/sbin/*) ;; *) return 0 ;; esac
  [ "$1" = "/usr/sbin/nologin" ] && return 0
  { [ -f "$1" ] || [ -h "$1" ]; } || return 0
  in_keep "$1" && return 0
  STRIP_LIST="$STRIP_LIST $1"
}
if command -v dpkg-query >/dev/null 2>&1; then
  for p in $STRIP_PKGS_DEB; do
    for f in $(dpkg-query -L "$p" 2>/dev/null); do collect "$f"; done
  done
elif command -v rpm >/dev/null 2>&1; then
  for p in $STRIP_PKGS_RPM; do
    for f in $(rpm -ql "$p" 2>/dev/null); do collect "$f"; done
  done
else
  # ubi-micro class: the rpmdb exists but there is no rpm BINARY take the
  # coreutils multicall, every symlink resolving to it, plus a fixed tool list
  # via command -v (a shell builtin, so it works without external binaries)
  for d in /bin /sbin /usr/bin /usr/sbin; do
    for f in "$d"/*; do
      [ -h "$f" ] || continue
      case "$(readlink -f "$f" 2>/dev/null)" in */coreutils) collect "$f" ;; esac
    done
  done
  collect /usr/bin/coreutils
  for t in grep sed awk gawk find xargs tar gzip vi diff hostname; do
    p="$(command -v "$t" 2>/dev/null || true)"; [ -n "$p" ] && collect "$p"
  done
fi

# 1. package MANAGER binaries + caches -----------------------------------
#    We remove the managers themselves, BUT deliberately KEEP the package
#    databases (/var/lib/dpkg/status, /var/lib/rpm) so Trivy/Grype/syft can
#    still enumerate installed packages for SBOM + vuln scanning. Deleting the
#    DBs (a previous bug) blinds the scanner and produces an empty SBOM.
#    drop bash from the dpkg DB while dpkg still exists (step 5 removes the binary)
if command -v dpkg >/dev/null 2>&1; then
  dpkg --purge --force-remove-essential --force-remove-protected --force-depends bash
fi
for pm in apt apt-get apt-key dpkg dpkg-query add-apt-repository \
          dnf dnf-3 microdnf yum rpm; do
  rm_bin "$pm"
done
rm -rf /var/lib/apt /var/cache/apt /etc/apt/sources.list.d \
       /var/lib/dnf /var/cache/dnf /var/cache/yum /usr/lib/apt \
       2>/dev/null || true
# NOTE: /var/lib/dpkg and /var/lib/rpm are intentionally preserved.

# 2. compilers / build tooling (harmless if absent) ----------------------
for t in gcc cc g++ cpp make ld as ar; do rm_bin "$t"; done

# 2b. network fetch tools the tarball fetch runs BEFORE harden, so a hardened
#     runtime has no reason to ship these.
for t in curl wget scp sftp ftp; do rm_bin "$t"; done

# 3. docs, man pages, locales, caches ------------------------------------
rm -rf /usr/share/doc /usr/share/man /usr/share/info \
       /usr/share/groff /usr/share/lintian /usr/share/linda \
       /var/cache/* /var/log/* /tmp/* /var/tmp/* \
       2>/dev/null || true
# keep only C/POSIX locale
find /usr/share/locale -mindepth 1 -maxdepth 1 -type d \
     ! -name 'C.*' ! -name 'en*' -exec rm -rf {} + 2>/dev/null || true

# 4. drop setuid/setgid bits (defence in depth) --------------------------
#    Loud when `find` is absent (ubi-micro-class bases): the sweep did NOT run ,
#    acceptable there (no setuid binaries ship), but never silently.
if command -v find >/dev/null 2>&1; then
  find / -xdev -perm /6000 -type f -exec chmod a-s {} + 2>/dev/null || true
else
  log "WARN: find not available setuid/setgid sweep skipped (minimal base)"
fi

# 5. shells removed near-last (the running interpreter stays in memory) --
for s in bash ash dash zsh ksh csh tcsh; do
  rm -f "/bin/$s" "/usr/bin/$s" 2>/dev/null || true
done
rm -f /bin/sh /usr/bin/sh 2>/dev/null || true

# 6. self-cleanup ---------------------------------------------------------
rm -rf /tmp/pki /usr/local/lib/hardening 2>/dev/null || true

# 7. unnecessary userland binaries (collected in step 0) ------------------
#    Policy: prod images carry NO triage tools the debug/* toolboxes do.
#    ONE rm invocation and it is the LAST command of the script: it deletes rm
#    itself (the exec'd binary keeps running from memory same trick as the
#    shell removal above). NOTE: dpkg/rpm DBs still list these packages (kept
#    for SBOM), so scanners keep reporting their CVEs accepted trade-off,
#    same as curl today (bash is purged from the DB in step 1 / the ubi-micro dbprune stage).
log "stripping userland binaries:$STRIP_LIST"
log "done"
if [ -n "$STRIP_LIST" ]; then
  # shellcheck disable=SC2086
  exec rm -f $STRIP_LIST
fi
