#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
jar_path="$(find "$repo_root/target" -maxdepth 1 -type f -name 'Kintsugi3DBuilder-*-shaded.jar' | sort | tail -n 1)"
tmp_dir="$repo_root/target/tmp"

if [[ -z "$jar_path" ]]; then
    echo "No shaded JAR found in target/. Build the app first with scripts/build-kintsugi3d.sh or mvn clean package -Plwjgl-natives-linux-amd64." >&2
    exit 1
fi

mkdir -p "$tmp_dir"

cd "$repo_root"

# HeadlessMain isn't the shaded jar's manifest main class (that's the GUI app), so it's invoked by
# fully-qualified name against the same jar's classpath. A live display connection (X11/Wayland) is
# still required to create the OpenGL context; on a display-less Linux server, prefix with xvfb-run.
#
# Keep the app's cache (preview images, specular-fit image cache) inside the repo instead of
# ~/.Kintsugi3DBuilder, so it can be wiped by just deleting cache/ and stays out of $HOME.
exec java -Djava.io.tmpdir="$tmp_dir" -DKintsugi3D.cacheDir="$repo_root/cache" -cp "$jar_path" kintsugi3d.builder.headless.HeadlessMain "$@"
