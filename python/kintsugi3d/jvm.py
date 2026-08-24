"""JVM bootstrap for the Kintsugi 3D Builder Python bindings.

Finds the shaded jar and starts an in-process JVM (via JPype) that stays warm for
the lifetime of the Python process - JPype cannot restart a JVM once shut down
within the same process, so start_jvm() is idempotent and shutdown is left to
interpreter exit rather than being tied to any individual pipeline's lifecycle.
"""

import atexit
import os
from pathlib import Path

import jpype

_shutdown_registered = False


def _repo_root() -> Path:
    # python/kintsugi3d/jvm.py -> python/kintsugi3d -> python -> repo root
    return Path(__file__).resolve().parents[2]


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
    jpype.startJVM(classpath=[str(jar)])

    if not _shutdown_registered:
        atexit.register(jpype.shutdownJVM)
        _shutdown_registered = True
