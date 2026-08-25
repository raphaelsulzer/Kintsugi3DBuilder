/*
 * Copyright (c) 2019 - 2026 Seth Berrier, Michael Tetzlaff, Jacob Buelow, Luke Denney, Ian Anderson, Zoe Cuthrell, Blane Suess, Isaac Tesch, Nathaniel Willius, Atlas Collins, Simon Cao
 * Copyright (c) 2019 The Regents of the University of Minnesota
 *
 * Licensed under GPLv3
 * ( http://www.gnu.org/licenses/gpl-3.0.html )
 *
 * This code is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 * This code is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 */

package kintsugi3d.builder.headless;

import kintsugi3d.builder.app.ApplicationFolders;
import kintsugi3d.builder.core.ConsoleProgressMonitor;
import kintsugi3d.builder.core.RenderableInstance;
import kintsugi3d.builder.core.SimpleLoadOptionsModel;
import kintsugi3d.builder.core.UserCancellationException;
import kintsugi3d.builder.core.ViewSet;
import kintsugi3d.builder.fit.SpecularFitProcess;
import kintsugi3d.builder.fit.settings.ExportSettings;
import kintsugi3d.builder.fit.settings.SpecularFitSettings;
import kintsugi3d.builder.io.ViewSetLoadOptions;
import kintsugi3d.builder.io.ViewSetWriterToVSET;
import kintsugi3d.builder.io.metashape.MetashapeModel;
import kintsugi3d.builder.resources.project.GraphicsResourcesImageSpace;
import kintsugi3d.builder.resources.project.MeshImportException;
import kintsugi3d.builder.resources.project.MissingImagesException;
import kintsugi3d.builder.rendering.ProjectRenderingEngine;
import kintsugi3d.gl.glfw.CanvasWindow;
import kintsugi3d.gl.interactive.InitializationException;
import kintsugi3d.gl.opengl.OpenGLContext;
import kintsugi3d.gl.opengl.OpenGLContextFactory;

import javax.xml.stream.XMLStreamException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Drives the import / specular fit / export pipeline without JavaFX or any visible window, for use by
 * batch tooling and (eventually) non-JVM callers such as a JPype-based Python binding. Deliberately avoids
 * ProjectInstanceManager and Global.state(), since those are wired for the GUI (JavaFX property models,
 * recent-projects bookkeeping, tab refresh) rather than the underlying algorithms.
 */
public final class HeadlessPipeline implements AutoCloseable
{
    private final CanvasWindow<OpenGLContext> window;
    private final OpenGLContext context;
    private RenderableInstance<OpenGLContext> renderable;

    /**
     * Resized/undistorted working copies of the source photos are written here during import (this is not
     * just a GUI thumbnail convenience - the color texture array is built from these). Defaults to a
     * subdirectory of the OS temp directory rather than the shared, size-capped application cache that the
     * GUI manages (ApplicationFolders.getPreviewImagesRootDirectory()), since a headless run has no GUI
     * "clean cache" button to reclaim that space and gains nothing from a cache the next run won't reuse.
     */
    private File previewCacheDirectory = new File(System.getProperty("java.io.tmpdir"), "kintsugi3d-headless-preview-cache");
    private File currentPreviewCacheSubdirectory;

    private HeadlessPipeline(CanvasWindow<OpenGLContext> window)
    {
        this.window = window;
        this.context = window.getContext();
    }

    /**
     * Overrides where working-resolution preview/undistortion copies of the source photos are written
     * during import (default: a subdirectory of the OS temp directory). Must be called before any load*
     * method.
     */
    public void setPreviewCacheDirectory(File directory)
    {
        this.previewCacheDirectory = directory;
    }

    /**
     * Creates an invisible 1x1 GLFW-backed OpenGL context, the same pattern already used by the production
     * render path (Rendering.java) and by the existing headless JUnit test (ImageReconstructionTests.java).
     * A live display connection (X11/Wayland/macOS window server) is still required to create the context;
     * on a display-less Linux server, run under something like xvfb-run.
     */
    public static HeadlessPipeline open()
    {
        CanvasWindow<OpenGLContext> window = OpenGLContextFactory.getInstance()
            .buildWindow("Kintsugi 3D Builder (headless)", 1, 1)
            .create();
        return new HeadlessPipeline(window);
    }

    public void loadFromVSETFile(File vsetFile, File supportingFilesDirectory) throws IOException, MeshImportException, InitializationException
    {
        load(newBuilder().loadVSETFile(vsetFile, supportingFilesDirectory));
    }

    public void loadFromLooseFiles(File cameraFile, ViewSetLoadOptions viewSetLoadOptions) throws Exception
    {
        load(newBuilder().loadLooseFiles(cameraFile, viewSetLoadOptions));
    }

    public void loadFromMetashapeModel(MetashapeModel model)
        throws IOException, MeshImportException, XMLStreamException, MissingImagesException, InitializationException
    {
        load(newBuilder().loadFromMetashapeModel(model));
    }

    /**
     * Loads a project from an already-constructed ViewSet (camera poses, intrinsics, per-view image
     * filenames, and geometry file reference already set on it) rather than parsing a project file - for
     * callers that build the ViewSet directly (e.g. from another tool's own camera/pose data via
     * ViewSet.getBuilder(...), the same pattern kintsugi3d.builder.io.ViewSetReaderFromRealityCaptureCSV
     * uses) instead of writing an intermediate file. useExistingViewSet doesn't re-run the preview-resolution
     * wiring that setImageLoadOptions normally performs when a view set is already present, so that's
     * applied explicitly here.
     */
    public void loadFromViewSet(ViewSet viewSet) throws InitializationException
    {
        SimpleLoadOptionsModel loadOptions = new SimpleLoadOptionsModel();
        viewSet.setPreviewImageResolution(loadOptions.getPreviewImageWidth(), loadOptions.getPreviewImageHeight());

        load(GraphicsResourcesImageSpace.getBuilderForContext(context)
            .setImageLoadOptions(loadOptions)
            .useExistingViewSet(viewSet));
    }

    private GraphicsResourcesImageSpace.Builder<OpenGLContext> newBuilder()
    {
        return GraphicsResourcesImageSpace.getBuilderForContext(context)
            .setImageLoadOptions(new SimpleLoadOptionsModel());
    }

    private void load(GraphicsResourcesImageSpace.Builder<OpenGLContext> builder) throws InitializationException
    {
        if (renderable != null)
        {
            renderable.close();
            renderable = null;
        }

        redirectPreviewCache(builder);
        renderable = ProjectRenderingEngine.createHeadless("headless", context, builder, new ConsoleProgressMonitor());
    }

    /**
     * By default, GraphicsResourcesImageSpace.Builder points its working-resolution preview/undistortion
     * copies at the shared, GUI-managed application cache (see the field comment on previewCacheDirectory).
     * This redirects that to our own, deletable-on-close location before the builder actually creates them.
     */
    private void redirectPreviewCache(GraphicsResourcesImageSpace.Builder<OpenGLContext> builder)
    {
        deleteCurrentPreviewCacheSubdirectory();

        var viewSet = builder.getViewSet();
        currentPreviewCacheSubdirectory = new File(previewCacheDirectory,
            String.format("%s_%dx%d", viewSet.getUUID(), viewSet.getPreviewWidth(), viewSet.getPreviewHeight()));
        viewSet.setRelativePreviewImagePathName(currentPreviewCacheSubdirectory.toString());
    }

    private void deleteCurrentPreviewCacheSubdirectory()
    {
        if (currentPreviewCacheSubdirectory == null || !currentPreviewCacheSubdirectory.exists())
        {
            currentPreviewCacheSubdirectory = null;
            return;
        }

        Path root = currentPreviewCacheSubdirectory.toPath();
        try (Stream<Path> paths = Files.walk(root))
        {
            paths.sorted(Comparator.reverseOrder()).forEach(path ->
            {
                try
                {
                    Files.deleteIfExists(path);
                }
                catch (IOException ignored)
                {
                    // Best-effort cleanup of our own scratch cache; leaving a stray temp file behind isn't fatal.
                }
            });
        }
        catch (IOException ignored)
        {
            // Best-effort cleanup of our own scratch cache; leaving a stray temp directory behind isn't fatal.
        }

        currentPreviewCacheSubdirectory = null;
    }

    /**
     * The imported project's GPU-backed resources (view set, geometry, textures). Available once a load*
     * method has completed successfully.
     */
    public GraphicsResourcesImageSpace<OpenGLContext> getResources()
    {
        if (renderable == null)
        {
            throw new IllegalStateException("No project has been loaded yet.");
        }

        return renderable.getResources();
    }

    /**
     * Runs the specular fit / decomposition process against the currently loaded project, calling
     * SpecularFitProcess directly (bypassing SpecularFitRequest's use of Global.state()).
     */
    public void runSpecularFit(SpecularFitSettings settings) throws IOException, UserCancellationException
    {
        if (settings.getOutputDirectory() == null)
        {
            settings.setOutputDirectory(getResources().getViewSet().getSupportingFilesDirectory());
        }

        if (settings.getImageCacheSettings().getCacheParentDirectory() == null)
        {
            // SpecularFitRequest (the GUI's fit-invocation class) sets this; headless calls
            // SpecularFitProcess directly, so without this it defaults to a relative path resolved
            // against the JVM's working directory instead of the standard application cache.
            settings.getImageCacheSettings().setCacheParentDirectory(ApplicationFolders.getFitCacheRootDirectory().toFile());
        }

        new SpecularFitProcess(settings).optimizeFitWithCache(getResources(), new ConsoleProgressMonitor());
        renderable.reloadShaders();
    }

    /**
     * Saves the currently loaded project's view set as a standalone .vset project file, so it can be
     * reopened later in the Kintsugi 3D Builder GUI (File > Open Project). The file's parent directory
     * becomes the view set's root directory - referenced paths (supporting files, geometry) are written
     * relative to it, so keep it inside (or alongside) the rest of the project's output.
     */
    public void saveVSET(File file) throws IOException
    {
        ViewSetWriterToVSET.getInstance().writeToFile(getResources().getViewSet(), file);
    }

    public void exportGltf(File outputDirectory, ExportSettings settings)
    {
        renderable.saveGLTF(outputDirectory, settings);
    }

    public void exportTextures(File materialDirectory) throws IOException
    {
        getResources().getTextureResources().saveAll(materialDirectory);
    }

    @Override
    public void close()
    {
        if (renderable != null)
        {
            renderable.close();
            renderable = null;
        }

        deleteCurrentPreviewCacheSubdirectory();
        context.close();
    }
}
