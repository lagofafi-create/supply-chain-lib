#!/usr/bin/env bash
# CI entrypoint for the library's OWN code, run in the PR pipeline (Jenkinsfile.ci).
set -euo pipefail
cd "$(dirname "$0")/.."

echo "== python unit tests (wrap.py, schema) =="
python3 -m pytest -q tests/

echo "== OPA policy tests (bundled example policy) =="
opa test resources/policy/

echo "== shellcheck (hardening) =="
if command -v shellcheck >/dev/null 2>&1; then
  shellcheck -S warning resources/hardening/*.sh
else
  echo "  (shellcheck not installed, skipping)"
fi

echo "ALL CHECKS PASSED"
