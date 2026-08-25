"""JVM bootstrap for the Kintsugi 3D Builder Python bindings.

Finds the shaded jar and starts an in-process JVM (via JPype) that stays warm for
the lifetime of the Python process - JPype cannot restart a JVM once shut down
within the same process, so start_jvm() is idempotent and shutdown is left to
interpreter exit rather than being tied to any individual pipeline's lifecycle.

start_jvm() also takes care of the two environment quirks HeadlessPipeline needs from
its caller, so scripts using these bindings don't have to replicate scripts/run-headless.sh
by hand: the JVM's shaders are loaded via paths relative to the process's working
directory, and creating the (invisible) OpenGL context still needs a live X/Wayland
display even though nothing is ever shown on screen.
"""

import atexit
import os
import shutil
import subprocess
import time
from pathlib import Path

import jpype

_shutdown_registered = False
_xvfb_process = None


def _repo_root() -> Path:
    # python/kintsugi3d/jvm.py -> python/kintsugi3d -> python -> repo root
    return Path(__file__).resolve().parents[2]


def _ensure_display() -> None:
    """Starts a headless Xvfb server and points DISPLAY at it, if no X/Wayland display is
    already available - the in-process equivalent of running under `xvfb-run`."""
    global _xvfb_process

    if os.environ.get("DISPLAY") or os.environ.get("WAYLAND_DISPLAY"):
        return

    xvfb_path = shutil.which("Xvfb")
    if xvfb_path is None:
        raise RuntimeError(
            "No DISPLAY/WAYLAND_DISPLAY set and Xvfb is not installed. Kintsugi 3D "
            "Builder's headless pipeline still creates a real (invisible) OpenGL context, "
            "so it needs one or the other. Install Xvfb (e.g. `apt install xvfb`) or run "
            "under an existing display."
        )

    display_num = 99
    while Path(f"/tmp/.X{display_num}-lock").exists():
        display_num += 1

    _xvfb_process = subprocess.Popen(
        [xvfb_path, f":{display_num}", "-screen", "0", "1280x1024x24"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    atexit.register(_xvfb_process.terminate)

    socket_path = Path(f"/tmp/.X11-unix/X{display_num}")
    for _ in range(100):
        if socket_path.exists():
            break
        time.sleep(0.1)
    else:
        raise RuntimeError(f"Xvfb did not come up within 10s on display :{display_num}.")

    os.environ["DISPLAY"] = f":{display_num}"


def find_default_jar() -> Path:
    """Locates the shaded jar, mirroring scripts/run-headless.sh's own discovery logic
    (KINTSUGI3D_JAR env var override, else the latest Kintsugi3DBuilder-*-shaded.jar under
    <repo_root>/target), so both stay in sync.
    """
    env_jar = os.environ.get("KINTSUGI3D_JAR")
    if env_jar:
        return Path(env_jar)

    candidates = sorted((_repo_root() / "target").glob("Kintsugi3DBuilder-*-shaded.jar"))
    if not candidates:
        raise FileNotFoundError(
            "No Kintsugi3DBuilder-*-shaded.jar found under target/. Build it first with "
            "scripts/build-kintsugi3d.sh (or mvn package), or set the KINTSUGI3D_JAR "
            "environment variable to an explicit jar path."
        )

    return candidates[-1]


def start_jvm(jar_path=None) -> None:
    """Starts the JVM with the shaded jar on the classpath, if it isn't already running."""
    global _shutdown_registered

    if jpype.isJVMStarted():
        return

    jar = Path(jar_path) if jar_path is not None else find_default_jar()

    _ensure_display()
    os.chdir(_repo_root())

    # Keep the app's cache (preview images, specular-fit image cache) inside the repo instead of
    # ~/.Kintsugi3DBuilder, so it can be wiped by just deleting cache/ and stays out of $HOME.
    cache_dir = _repo_root() / "cache"
    jpype.startJVM(f"-DKintsugi3D.cacheDir={cache_dir}", classpath=[str(jar)])

    if not _shutdown_registered:
        atexit.register(jpype.shutdownJVM)
        _shutdown_registered = True
