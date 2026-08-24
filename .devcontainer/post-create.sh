#!/usr/bin/env bash
set -euo pipefail

git config --global --add safe.directory "$PWD"

mkdir -p "$HOME/.cache/lwjgl"

echo "Using $(java -version 2>&1 | head -n 1)"
echo "Using $(mvn -version | head -n 1)"
echo "Priming Maven dependencies for the Linux profile"

mvn -Plwjgl-natives-linux-amd64 -DskipTests dependency:go-offline