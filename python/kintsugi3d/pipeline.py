"""Python bindings for kintsugi3d.builder.headless.HeadlessPipeline.

This is a thin wrapper, not a reimplementation: it starts the JVM, converts a handful
of common Python types (str/Path/bool) into their Java equivalents, and otherwise hands
back real Java objects for callers to configure further via JPype. There is no parallel
Python type hierarchy for every Java settings class - see new_specular_fit_settings()
and new_export_settings() below, and Kintsugi3DPipeline.resources, for that escape hatch.

Java exceptions propagate as jpype.JException; they are not wrapped in a custom
Python exception hierarchy.
"""

import math
import os
from pathlib import Path

import jpype

from .jvm import start_jvm

__all__ = [
    "Kintsugi3DPipeline",
    "view_set_load_options",
    "pose_from_quaternion_translation",
    "build_view_set",
    "new_specular_fit_settings",
    "new_export_settings",
    "new_usdz_export_settings",
]


def _jfile(path):
    if path is None:
        return None
    return jpype.java.io.File(os.fspath(path))


class Kintsugi3DPipeline:
    """Drives the import / specular fit / export pipeline headlessly, via an in-process
    JPype call into kintsugi3d.builder.headless.HeadlessPipeline. Use as a context manager:

        with Kintsugi3DPipeline() as pipeline:
            pipeline.load_from_vset("project.vset", "project_supporting_files")
            pipeline.run_specular_fit(2048)
            pipeline.export_gltf("output")

    Creating a pipeline (open()) creates a real, invisible GLFW/OpenGL context, so the
    Python process itself needs a live display connection (X11/Wayland/macOS window
    server) - run under xvfb-run on a display-less Linux machine.
    """

    def __init__(self, jar_path=None):
        start_jvm(jar_path)
        self._java = jpype.JClass("kintsugi3d.builder.headless.HeadlessPipeline").open()

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        self.close()

    def close(self):
        """Releases the loaded project's GPU resources and the OpenGL context. Does not
        shut down the JVM - a new Kintsugi3DPipeline can still be opened afterward in the
        same process."""
        self._java.close()

    def set_preview_cache_directory(self, directory):
        """Must be called before any load_from_* method. See HeadlessPipeline's javadoc
        for why this exists: it keeps working-resolution preview copies out of the
        shared, GUI-managed application cache."""
        self._java.setPreviewCacheDirectory(_jfile(directory))

    def load_from_vset(self, vset_file, supporting_files_directory):
        self._java.loadFromVSETFile(_jfile(vset_file), _jfile(supporting_files_directory))

    def load_from_loose_files(self, camera_file, load_options):
        """load_options: a Java ViewSetLoadOptions object, e.g. from view_set_load_options()."""
        self._java.loadFromLooseFiles(_jfile(camera_file), load_options)

    def load_from_view_set(self, view_set):
        """view_set: a Java ViewSet object, e.g. from build_view_set()."""
        self._java.loadFromViewSet(view_set)

    def load_from_metashape(self, psx_file, chunk_label=None):
        """Imports a full Metashape .psx project. If chunk_label is None, the project's
        currently-active chunk is used; otherwise the chunk with that label is selected."""
        document = jpype.JClass("kintsugi3d.builder.io.metashape.MetashapeDocument")(os.fspath(psx_file))

        if chunk_label is None:
            chunk = document.getSelectedChunk()
        else:
            chunk = next((c for c in document.getChunks() if str(c.getLabel()) == chunk_label), None)
            if chunk is None:
                raise ValueError(f"No chunk named {chunk_label!r} found in the Metashape project.")

        self._java.loadFromMetashapeModel(chunk.getSelectedModel())

    @property
    def resources(self):
        """The imported project's GPU-backed resources (view set, geometry, textures) as
        the raw Java GraphicsResourcesImageSpace object. Available once a load_from_*
        method has completed successfully."""
        return self._java.getResources()

    def run_specular_fit(self, texture_size, *, basis_count=None, output_directory=None):
        """Runs the specular fit / decomposition process against the currently loaded
        project. texture_size is the output texture resolution (always square - same
        convention as the GUI's "textureSize" project setting and every texture atlas
        AliceVision/most other tools produce)."""
        settings = new_specular_fit_settings(texture_size)

        if basis_count is not None:
            settings.getSpecularBasisSettings().setBasisComplexity(basis_count)

        if output_directory is not None:
            settings.setOutputDirectory(_jfile(output_directory))

        self._java.runSpecularFit(settings)

    def export_gltf(self, output_directory, settings=None):
        """settings: a Java ExportSettings object (see new_export_settings()), or None to
        use the defaults."""
        self._java.exportGltf(_jfile(output_directory), settings if settings is not None else new_export_settings())

    def export_usdz(self, output_directory, settings=None, *, metallic=False):
        """Exports the currently loaded/fit project to a self-contained output_directory/model.usdz,
        via Kintsugi 3D Builder's own USDZ exporter (kintsugi3d.builder.io.usdz) - requires a real
        usdz-exporter-*-linux/macos/windows.exe binary (from
        https://github.com/PoctorDepper/usdz-exporter/releases) present in this repo's own bin/
        directory (ApplicationFolders.getAdditionalBinDirectory(), resolved relative to the JVM's
        working directory - start_jvm() already os.chdir()s to this repo's root, so bin/ here is the
        right place regardless of the calling script's own cwd).

        settings: a Java ExportSettings object already configured with a USDZ exporter factory (see
        new_usdz_export_settings()), or None to build one from `metallic` for you.

        metallic: ignored if settings is given. Selects USDZMetallicExporterFactory (albedo/ORM) over
        the default USDZSpecularExporterFactory (diffuse/specular/roughness) - mirrors the GUI's
        ExportType.USDZ_METALLIC vs. ExportType.USDZ_SPECULAR.
        """
        self._java.exportUsdz(
            _jfile(output_directory),
            settings if settings is not None else new_usdz_export_settings(metallic=metallic))

    def export_textures(self, material_directory):
        self._java.exportTextures(_jfile(material_directory))

    def save_vset(self, file):
        """Saves the currently loaded project as a standalone .vset project file, so it can be
        reopened later in the Kintsugi 3D Builder GUI (File > Open Project). The file's parent
        directory becomes the view set's root directory - referenced paths are written relative
        to it, so keep it inside (or alongside) the rest of the project's output."""
        self._java.saveVSET(_jfile(file))

    def calibrate_light_position(self, x, y):
        """Sets the physical offset between the flash and the lens optical axis - what the GUI's
        interactive "Light Calibration" task (LightCalibrationController.confirm() ->
        IOModel.applyLightCalibration()) writes when the user drags its X/Y sliders and confirms.
        This is a thin wrapper around the same underlying call
        (GraphicsResources.updateLightCalibration(Vector3)), reached here via `resources` rather
        than through any GUI-only plumbing (ProjectInstanceManager/Global.state()).

        x, y: offset in camera-local space (x = right, y = up, from the lens's point of view),
        in the same world-space units as the loaded geometry/camera poses - NOT pixels, and not
        the on-screen slider's arbitrary display range. A flash mounted to the camera's right and
        above the lens needs positive x and positive y. Applied uniformly to every view via
        ViewSet.setLightPosition() (see GraphicsResourcesBase.updateLightCalibration()), the same
        way build_view_set() gives every view a single shared light index - correct for a rig
        with one on-axis flash held in a fixed physical position relative to the lens for the
        whole capture.

        There is no headless equivalent of the interactive part of this task: the GUI's
        split-screen live preview is how a user *finds* the right x/y by eye (nudging the
        sliders until specular highlights line up). This call only *applies* an already-known
        offset - measured directly on the physical rig, or determined once interactively via the
        GUI on a representative project and reused here for every subsequent headless run of the
        same physical camera+flash mount. Independent of calibrateLightIntensities() (light
        *intensity*, from camera-to-centroid distance) - call in either order relative to it.
        """
        Vector3 = jpype.JClass("kintsugi3d.gl.vecmath.Vector3")
        self.resources.updateLightCalibration(Vector3(float(x), float(y), 0.0))


def view_set_load_options(camera_file, *, project_root=None, supporting_files_directory=None,
                           full_res_image_directory=None, full_res_images_need_undistort=False,
                           geometry_file=None, masks_directory=None,
                           orientation_view_name=None, orientation_view_rotation=0.0):
    """Builds a Java ViewSetLoadOptions object (with its nested ViewSetDirectories) from
    plain Python arguments, for use with Kintsugi3DPipeline.load_from_loose_files(). This
    accepts an Agisoft Metashape XML export or a RealityCapture CSV export as camera_file -
    Kintsugi3D Builder dispatches between the two readers by file extension.
    """
    options = jpype.JClass("kintsugi3d.builder.io.ViewSetLoadOptions")()

    options.mainDirectories.projectRoot = _jfile(project_root)
    options.mainDirectories.supportingFilesDirectory = _jfile(supporting_files_directory)
    options.mainDirectories.fullResImageDirectory = _jfile(full_res_image_directory)
    options.mainDirectories.fullResImagesNeedUndistort = full_res_images_need_undistort

    options.geometryFile = _jfile(geometry_file)
    options.masksDirectory = _jfile(masks_directory)
    options.orientationViewName = orientation_view_name
    options.orientationViewRotation = orientation_view_rotation

    return options


def pose_from_quaternion_translation(qw, qx, qy, qz, tx, ty, tz):
    """Builds a 16-float row-major world-to-camera 4x4 matrix [R t; 0 1] from a
    world-to-camera quaternion (w, x, y, z order) and translation, in COLMAP's own camera
    convention (X right, Y down, Z forward - i.e. the camera looks down its local +Z axis).

    kintsugi3d.builder.core.ViewSet does NOT share that convention directly - it expects
    camera poses in OpenGL's convention instead (X right, Y up, Z backward - the camera
    looks down its local -Z axis), the same convention every other Kintsugi3D reader
    (e.g. ViewSetReaderFromAgisoftXML's Metashape import, verified directly against
    Kintsugi3DBuilder's own Metashape test data) converts its source tool's camera
    representation into before calling setCurrentCameraPose(). Converting from COLMAP's
    convention to OpenGL's is a 180-degree rotation about the local X axis, i.e. negating
    the whole Y and Z rows (rotation AND translation) of the naive [R t; 0 1] COLMAP
    matrix - skipping this produces exactly the symptom that caught the bug: camera
    positions and the mesh appearing correct independently, but flipped/wrong relative to
    each other once loaded into Kintsugi3D.
    """
    norm = math.sqrt(qw * qw + qx * qx + qy * qy + qz * qz)
    qw, qx, qy, qz = qw / norm, qx / norm, qy / norm, qz / norm

    return [
        1 - 2 * (qy * qy + qz * qz), 2 * (qx * qy - qz * qw), 2 * (qx * qz + qy * qw), tx,
        -(2 * (qx * qy + qz * qw)), -(1 - 2 * (qx * qx + qz * qz)), -(2 * (qy * qz - qx * qw)), -ty,
        -(2 * (qx * qz - qy * qw)), -(2 * (qy * qz + qx * qw)), -(1 - 2 * (qx * qx + qy * qy)), -tz,
        0.0, 0.0, 0.0, 1.0,
    ]


def build_view_set(project_root, cameras, *, supporting_files_directory=None,
                    full_res_image_directory=None, geometry_file=None, masks_directory=None):
    """Builds a Java ViewSet directly from plain pose/intrinsics data - for callers that
    already have per-view camera data (from any SfM tool) rather than a Kintsugi3D project
    file. This is the same construction pattern
    kintsugi3d.builder.io.ViewSetReaderFromRealityCaptureCSV uses (ViewSet.getBuilder(...) +
    one DistortionProjection per distinct set of intrinsics), just driven from Python
    instead of a specific file format's Java reader - so there's no intermediate file
    format to get right, and no unit conversion: DistortionProjection's width/height/
    fx/fy/cx/cy are plain pixel values, not physical sensor millimeters.

    cameras: a list of dicts, one per view, each with:
      'pose': 16 floats, row-major world-to-camera 4x4 matrix (see pose_from_quaternion_translation)
      'width', 'height': int, in pixels
      'fx', 'fy', 'cx', 'cy': float, in pixels
      'k1', 'k2', 'k3', 'k4', 'p1', 'p2': float, optional, default 0.0 (radial/tangential distortion)
      'image_file': str or Path - resolved against full_res_image_directory

    Cameras sharing identical intrinsics are grouped into a single projection, mirroring
    ViewSetReaderFromRealityCaptureCSV's own grouping.

    masks_directory: if given, each camera's mask is looked up in this directory by filename
    stem (e.g. RIG_42833.JPG -> RIG_42833.png) and set via setCurrentMaskFile(). Without this,
    every pixel of every source photo - including large background regions the mesh doesn't
    even cover - is treated as valid scene data, which can badly contaminate per-texel average
    color computation (e.g. a solid black backdrop pulling the whole fit toward black/gray).

    Returns the raw Java ViewSet object, for use with Kintsugi3DPipeline.load_from_view_set().
    """
    ViewSetClass = jpype.JClass("kintsugi3d.builder.core.ViewSet")
    DistortionProjection = jpype.JClass("kintsugi3d.builder.core.DistortionProjection")
    Matrix4 = jpype.JClass("kintsugi3d.gl.vecmath.Matrix4")
    Vector4 = jpype.JClass("kintsugi3d.gl.vecmath.Vector4")
    Vector3 = jpype.JClass("kintsugi3d.gl.vecmath.Vector3")

    builder = ViewSetClass.getBuilder(_jfile(project_root), len(cameras))

    if geometry_file is not None:
        builder.setGeometryFile(_jfile(geometry_file))

    projection_indices = {}
    for camera in cameras:
        intrinsics_key = (
            camera["width"], camera["height"], camera["fx"], camera["fy"],
            camera["cx"], camera["cy"], camera.get("k1", 0.0), camera.get("k2", 0.0),
            camera.get("k3", 0.0), camera.get("k4", 0.0), camera.get("p1", 0.0), camera.get("p2", 0.0),
        )

        if intrinsics_key not in projection_indices:
            projection_indices[intrinsics_key] = builder.getNextCameraProjectionIndex()
            builder.addCameraProjection(DistortionProjection(
                float(camera["width"]), float(camera["height"]),
                float(camera["fx"]), float(camera["fy"]),
                float(camera["cx"]), float(camera["cy"]),
                float(camera.get("k1", 0.0)), float(camera.get("k2", 0.0)),
                float(camera.get("k3", 0.0)), float(camera.get("k4", 0.0)),
                float(camera.get("p1", 0.0)), float(camera.get("p2", 0.0)), 0.0))

        pose = [float(v) for v in camera["pose"]]
        pose_matrix = Matrix4.fromRows(
            Vector4(*pose[0:4]), Vector4(*pose[4:8]), Vector4(*pose[8:12]), Vector4(*pose[12:16]))

        camera_builder = (builder.setCurrentCameraPose(pose_matrix)
            .setCurrentCameraProjectionIndex(projection_indices[intrinsics_key])
            .setCurrentLightIndex(0)
            .setCurrentImageFile(jpype.java.io.File(os.fspath(camera["image_file"]))))

        if masks_directory is not None:
            mask_file = Path(masks_directory) / (Path(camera["image_file"]).stem + ".png")
            camera_builder = camera_builder.setCurrentMaskFile(_jfile(mask_file))

        camera_builder.commitCurrentCameraPose()

    # Light co-located with the camera (zero position), unit (not zero) intensity - treats every photo as
    # lit by a normalized, uncalibrated flash, the same convention ViewSetReaderFromAgisoftXML uses for
    # sources with no calibrated light data. A zero intensity here divides every texel's reflectance by
    # zero in average.frag (rgb / attenuatedIntensity), poisoning it to Infinity/NaN.
    builder.addLight(Vector3.ZERO, Vector3(1.0))

    if full_res_image_directory is not None:
        builder.setFullResImageDirectory(_jfile(full_res_image_directory))

    if masks_directory is not None:
        # getMask() re-joins this directory with each per-camera mask filename set above via
        # setCurrentMaskFile() - both are required, not just one or the other.
        builder.setMasksDirectory(_jfile(masks_directory))

    if supporting_files_directory is not None:
        builder.setRelativeSupportingFilesPathName(os.fspath(supporting_files_directory))

    return builder.finish()


def new_specular_fit_settings(texture_size):
    """Returns a real Java SpecularFitSettings object for the given (square) output
    texture resolution, for callers who need to configure it beyond
    run_specular_fit()'s basis_count/output_directory convenience parameters."""
    return jpype.JClass("kintsugi3d.builder.fit.settings.SpecularFitSettings")(texture_size, texture_size)


def new_export_settings():
    """Returns a real Java ExportSettings object with its normal defaults, for callers
    who need to configure it beyond export_gltf()'s defaults."""
    return jpype.JClass("kintsugi3d.builder.fit.settings.ExportSettings")()


def new_usdz_export_settings(*, metallic=False):
    """Returns a Java ExportSettings object configured for USDZ export (see
    Kintsugi3DPipeline.export_usdz()) - a plain new_export_settings() would keep the GLTF-flavored
    default exporter factory, which export_usdz() explicitly requires callers to override.

    Also forces shouldSaveTextures (and shouldAppendModelNameToTextures) on, mirroring
    ExportTexturesRequest.getExportSettingsFromProject() - the GUI's own export path always turns
    these on regardless of export type. This isn't just a naming nicety: ProjectRenderingEngine.saveGLTF
    only calls the material exporter's saveTextures()/postExport() hooks (where the USDZ exporters
    actually stage textures and invoke the external usdz-exporter binary that turns the plain glTF
    into a real USDZ archive) when shouldSaveTextures() is true - with it left at ExportSettings'
    default of false, export_usdz() would silently write a bare glTF binary named "model.usdz"
    instead of an actual USDZ.

    metallic: USDZMetallicExporterFactory (albedo/ORM) instead of the default
    USDZSpecularExporterFactory (diffuse/specular/roughness) - see kintsugi3d.builder.io.ExportType's
    USDZ_METALLIC vs. USDZ_SPECULAR, the same choice the GUI's export dialog offers.
    """
    settings = new_export_settings()
    settings.setShouldSaveTextures(True)
    settings.setShouldAppendModelNameToTextures(True)
    factory_class_name = "USDZMetallicExporterFactory" if metallic else "USDZSpecularExporterFactory"
    factory = jpype.JClass(f"kintsugi3d.builder.io.usdz.{factory_class_name}").getInstance()
    settings.setExporterFactory(factory)
    return settings
