# kintsugi3d (Python bindings)

Python bindings for Kintsugi 3D Builder's headless pipeline
(`kintsugi3d.builder.headless.HeadlessPipeline` in the Java source tree): import a
project, run the specular fit / decomposition, and export a glTF model or texture
maps, without JavaFX or any visible window.

This package is a thin JPype wrapper, not a reimplementation - it starts an
in-process JVM with the app's shaded jar on the classpath and calls straight into
the real Java classes. Anything not covered by the convenience methods below (e.g.
a less common `ExportSettings` field) is still reachable: `new_export_settings()` /
`new_specular_fit_settings()` return the real Java objects, and their setters can be
called directly from Python.

## Setup

Build the shaded jar first, from the repo root:

```
scripts/build-kintsugi3d.sh
```

Then install this package (editable install picks up the jar via its default
discovery, from `<repo_root>/target/`):

```
pip install -e python/
```

If the jar lives somewhere else, set `KINTSUGI3D_JAR=/path/to/Kintsugi3DBuilder-*-shaded.jar`,
or pass `jar_path=` explicitly to `Kintsugi3DPipeline(...)`.

**Display requirement:** `Kintsugi3DPipeline()` creates a real (invisible) GLFW/OpenGL
context, so the Python process needs a live display connection (X11/Wayland/macOS
window server) even though nothing is shown on screen. On a display-less Linux
machine, run under `xvfb-run`, same as `scripts/run-headless.sh`.

## Usage

```python
from kintsugi3d import Kintsugi3DPipeline, view_set_load_options

with Kintsugi3DPipeline() as pipeline:
    # Import from a Metashape XML / RealityCapture CSV export + mesh + photo folder:
    load_options = view_set_load_options(
        "path/to/cameras.xml",
        geometry_file="path/to/mesh.obj",
        full_res_image_directory="path/to/photos",
        supporting_files_directory="path/to/output",
    )
    pipeline.load_from_loose_files("path/to/cameras.xml", load_options)

    # Or: pipeline.load_from_vset("project.vset", "project_supporting_files")
    # Or: pipeline.load_from_metashape("project.psx")

    pipeline.run_specular_fit(2048, basis_count=8)

    pipeline.export_gltf("output")
    pipeline.export_textures("output/textures")
```

## Error handling

Java exceptions surface as `jpype.JException` (the underlying Java exception is
available via its `.stacktrace()`/`.__cause__`) - they are not translated into a
custom Python exception hierarchy.

## Scope

This package only wraps Kintsugi 3D Builder's own pipeline steps. Converting output
from another photogrammetry tool (e.g. AliceVision/COLMAP) into a format Kintsugi 3D
Builder can import is out of scope here and lives in whatever project produces that
data.
