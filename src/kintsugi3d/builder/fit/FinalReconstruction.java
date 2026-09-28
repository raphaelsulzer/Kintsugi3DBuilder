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

package kintsugi3d.builder.fit;

import kintsugi3d.builder.core.ReadonlyViewSet;
import kintsugi3d.builder.core.TextureResolution;
import kintsugi3d.builder.core.metrics.ColorAppearanceRMSE;
import kintsugi3d.builder.fit.settings.ReconstructionSettings;
import kintsugi3d.builder.rendering.ImageReconstruction;
import kintsugi3d.builder.rendering.ReconstructionView;
import kintsugi3d.builder.resources.project.ReadonlyGraphicsResources;
import kintsugi3d.builder.resources.project.specular.TextureResources;
import kintsugi3d.gl.builders.ProgramBuilder;
import kintsugi3d.gl.core.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import kintsugi3d.gl.vecmath.DoubleVector3;
import org.lwjgl.BufferUtils;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.*;

public class FinalReconstruction<ContextType extends Context<ContextType>>
{
    private static final Logger LOG = LoggerFactory.getLogger(FinalReconstruction.class);
    private final ReadonlyGraphicsResources<ContextType> resources;
    private final ReconstructionSettings reconstructionSettings;

    /**
     * Whether saved reconstructions are written in the encoded space the RMSE is computed in,
     * rather than as the raw linear framebuffer.
     */
    private boolean saveEncoded;

    public FinalReconstruction(ReadonlyGraphicsResources<ContextType> resources, TextureResolution textureResolution, ReconstructionSettings reconstructionSettings)
    {
        this.resources = resources;
        this.reconstructionSettings = reconstructionSettings;
    }

    /**
     * Writes saved reconstructions in the same encoded space the RMSE is computed in, instead of
     * as the raw linear framebuffer.
     *
     * The default save is a debug artefact and is close to useless as data: the framebuffer holds
     * the BRDF response *before* it is multiplied by incident radiance and passed through the view
     * set's luminance encoding, so on a real capture its values land around 0.003 and quantise to
     * roughly one 8-bit level. Measured on DiLiGenT-MV, comparing such a save against the original
     * photograph gives 21.7 dB where the RMSE reports the equivalent of 38.6 dB, and no single
     * global gain reconciles them because incident radiance varies per pixel.
     *
     * With this enabled the saved image is {@code encode(linear * incidentRadiance)} - exactly the
     * quantity the encoded RMSE compares against the ground truth - so the files can be scored
     * directly against the source photographs.
     */
    public FinalReconstruction<ContextType> setSaveEncoded(boolean saveEncoded)
    {
        this.saveEncoded = saveEncoded;
        return this;
    }

    public List<Map<String, ColorAppearanceRMSE>> reconstruct(TextureResources<ContextType> specularFit,
                                                              Map<String, ProgramBuilder<ContextType>> reconstructionProgramBuilders,
                                                              ProgramBuilder<ContextType> incidentRadianceProgramBuilder,
                                                              File debugDirectory, File groundTruthDirectory)
    {
        if (debugDirectory != null)
        {
            for (String reconstructionType : reconstructionProgramBuilders.keySet())
            {
                // Create directory for reconstructions from basis functions
                new File(debugDirectory, reconstructionType).mkdirs();
            }
        }

        if (groundTruthDirectory != null)
        {
            groundTruthDirectory.mkdirs();
        }

        // Use the same view set as for fitting if another wasn't specified for reconstruction.
        ReadonlyViewSet reconstructionViewSet;
        if (reconstructionSettings.getReconstructionViewSet() != null)
        {
            reconstructionViewSet = reconstructionSettings.getReconstructionViewSet();
        }
        else
        {
            reconstructionViewSet = resources.getViewSet();
        }

        try (@SuppressWarnings("MismatchedQueryAndUpdateOfCollection") ResourceMap<String, ProgramObject<ContextType>> programMap
                = new ResourceMap<>(reconstructionProgramBuilders.size());
            ImageReconstruction<ContextType> reconstruction = new ImageReconstruction<>(
                reconstructionViewSet,
                builder -> builder
                    .addColorAttachment(ColorFormat.RGBA32F)
                    .addDepthAttachment(),
                builder -> builder
                    .addColorAttachment(ColorFormat.RGBA32F)
                    .addDepthAttachment(),
                incidentRadianceProgramBuilder, resources))
        {
            Map<String, Drawable<ContextType>> drawableMap = new HashMap<>(reconstructionProgramBuilders.size());

            for (var entry : reconstructionProgramBuilders.entrySet())
            {
                ProgramObject<ContextType> program = entry.getValue().createProgram();
                programMap.put(entry.getKey(), program);
                resources.setupShaderProgram(program);
                drawableMap.put(entry.getKey(), resources.createDrawable(program));
            }

            List<Map<String, ColorAppearanceRMSE>> rmseOut = new ArrayList<>(reconstructionViewSet.getCombinedCameraPoseCount());

            // Run the reconstruction and save the results to file
            for (ReconstructionView<ContextType> view : reconstruction)
            {
                LOG.info("View {}:", view.getIndex());
                // Allocate hash map for the current view
                rmseOut.add(new HashMap<>(drawableMap.size()));

                for (var entry : drawableMap.entrySet())
                {
                    ColorAppearanceRMSE rmse = view.reconstruct(entry.getValue());

                    if (debugDirectory != null)
                    {
                        if (saveEncoded)
                        {
                            saveEncodedImageToFile(new File(debugDirectory, entry.getKey()), view.getIndex(),
                                view, reconstructionViewSet);
                        }
                        else
                        {
                            saveImageToFile(new File(debugDirectory, entry.getKey()), view.getIndex(), view.getReconstructionFramebuffer());
                        }
                    }

                    // Record RMSE
                    rmseOut.get(view.getIndex()).put(entry.getKey(), rmse);

                    LOG.info("{}: \nencoded ground truth = {}\nnormalized sRGB = {}\nnormalized linear = {}",
                        entry.getKey(), rmse.getEncodedGroundTruth(), rmse.getNormalizedSRGB(), rmse.getNormalizedLinear());
                }
            }

            return rmseOut;
        }
        // NoSuchElementException thrown if there's an error reading a ground truth image from the view set
        catch (IOException | NoSuchElementException e)
        {
            LOG.error("An error occurred during reconstruction:", e);
            return null;
        }
    }


    private static int clampTo255(double v)
    {
        return (int) Math.round(Math.max(0.0, Math.min(255.0, v)));
    }

    /**
     * Saves one reconstruction as {@code encode(linear * incidentRadiance)} - see setSaveEncoded().
     */
    private void saveEncodedImageToFile(File outputDirectory, int k, ReconstructionView<ContextType> view,
                                        ReadonlyViewSet viewSet)
    {
        try
        {
            outputDirectory.mkdirs();
            ReadableFramebuffer<ContextType> framebuffer = view.getReconstructionFramebuffer();
            FramebufferSize size = framebuffer.getSize();
            FloatBuffer buffer = BufferUtils.createFloatBuffer(size.width * size.height * 4);
            framebuffer.getTextureReaderForColorAttachment(0).readFloatingPointRGBA(buffer);

            BufferedImage image = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
            for (int p = 0; p < size.width * size.height; p++)
            {
                int argb = 0;
                if (buffer.get(4 * p + 3) > 0.0f)
                {
                    DoubleVector3 linear = new DoubleVector3(
                        buffer.get(4 * p), buffer.get(4 * p + 1), buffer.get(4 * p + 2))
                        .applyOperator(z -> Double.isNaN(z) ? 0.0 : z);
                    DoubleVector3 encoded =
                        viewSet.getLuminanceEncoding().encode(linear.times(view.getIncidentRadiance(p)));
                    argb = 0xFF000000
                        | (clampTo255(encoded.x) << 16)
                        | (clampTo255(encoded.y) << 8)
                        | clampTo255(encoded.z);
                }
                // The framebuffer is bottom-up; BufferedImage rows run top-down.
                image.setRGB(p % size.width, size.height - 1 - (p / size.width), argb);
            }

            ImageIO.write(image, "PNG", new File(outputDirectory, String.format("%04d.png", k)));
        }
        catch (IOException e)
        {
            LOG.error("An error occurred while saving encoded image:", e);
        }
    }

    private void saveImageToFile(File outputDirectory, int k, ReadableFramebuffer<ContextType> framebuffer)
    {
        try
        {
            String filename = String.format("%04d.png", k);
            framebuffer.getTextureReaderForColorAttachment(0).saveToFile("PNG", new File(outputDirectory, filename));
        }
        catch (IOException e)
        {
            LOG.error("An error occurred while saving image:", e);
        }
    }

    private void saveImageToFile(File outputDirectory, String filename, ReadableFramebuffer<ContextType> framebuffer)
    {
        try
        {
            framebuffer.getTextureReaderForColorAttachment(0).saveToFile("PNG", new File(outputDirectory, filename));
        }
        catch (IOException e)
        {
            LOG.error("An error occurred while saving image:", e);
        }
    }
}
