"""Python bindings for kintsugi3d.builder.headless.HeadlessPipeline.

This is a thin wrapper, not a reimplementation: it starts the JVM, converts a handful
of common Python types (str/Path/bool) into their Java equivalents, and otherwise hands
back real Java objects for callers to configure further via JPype. There is no parallel
Python type hierarchy for every Java settings class - see new_specular_fit_settings()
and new_export_settings() below, and Kintsugi3DPipeline.resources, for that escape hatch.

Java exceptions propagate as jpype.JException; they are not wrapped in a custom
Python exception hierarchy.
"""

import os

import jpype

from .jvm import start_jvm

__all__ = [
    "Kintsugi3DPipeline",
    "view_set_load_options",
    "new_specular_fit_settings",
    "new_export_settings",
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
            pipeline.run_specular_fit(2048, 2048)
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

    def run_specular_fit(self, width, height, *, basis_count=None, output_directory=None):
        """Runs the specular fit / decomposition process against the currently loaded
        project. width/height are the output texture resolution."""
        settings = new_specular_fit_settings(width, height)

        if basis_count is not None:
            settings.getSpecularBasisSettings().setBasisComplexity(basis_count)

        if output_directory is not None:
            settings.setOutputDirectory(_jfile(output_directory))

        self._java.runSpecularFit(settings)

    def export_gltf(self, output_directory, settings=None):
        """settings: a Java ExportSettings object (see new_export_settings()), or None to
        use the defaults."""
        self._java.exportGltf(_jfile(output_directory), settings if settings is not None else new_export_settings())

    def export_textures(self, material_directory):
        self._java.exportTextures(_jfile(material_directory))


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


def new_specular_fit_settings(width, height):
    """Returns a real Java SpecularFitSettings object for the given output texture
    resolution, for callers who need to configure it beyond run_specular_fit()'s
    basis_count/output_directory convenience parameters."""
    return jpype.JClass("kintsugi3d.builder.fit.settings.SpecularFitSettings")(width, height)


def new_export_settings():
    """Returns a real Java ExportSettings object with its normal defaults, for callers
    who need to configure it beyond export_gltf()'s defaults."""
    return jpype.JClass("kintsugi3d.builder.fit.settings.ExportSettings")()
