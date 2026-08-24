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

import kintsugi3d.builder.fit.settings.ExportSettings;
import kintsugi3d.builder.fit.settings.SpecularFitSettings;
import kintsugi3d.builder.io.metashape.MetashapeChunk;
import kintsugi3d.builder.io.metashape.MetashapeDocument;
import kintsugi3d.builder.io.metashape.MetashapeModel;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Thin CLI wrapper around HeadlessPipeline, for smoke-testing and scripting the import / specular fit /
 * export pipeline without a JPype (or similar) binding. HeadlessPipeline itself, not this class, is the
 * intended integration surface for a future Python binding.
 *
 * Usage:
 *   HeadlessMain --vset <project.vset> --output <dir> [options]
 *   HeadlessMain --metashape <project.psx> [--chunk <label>] --output <dir> [options]
 *
 * Options:
 *   --width <int>          texture width for the fit (default 2048)
 *   --height <int>         texture height for the fit (default 2048)
 *   --basis-count <int>    number of specular basis materials (default: SpecularFitSettings default)
 *   --skip-fit             import and export only; skip the specular fit step
 *   --gltf                 export a glTF/GLB model
 *   --textures             export material texture maps
 */
public final class HeadlessMain
{
    private HeadlessMain()
    {
    }

    public static void main(String[] args) throws Exception
    {
        Map<String, String> named = new HashMap<>();
        java.util.Set<String> flags = new java.util.HashSet<>();
        for (int i = 0; i < args.length; i++)
        {
            if (!args[i].startsWith("--"))
            {
                continue;
            }

            String key = args[i].substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--"))
            {
                named.put(key, args[++i]);
            }
            else
            {
                flags.add(key);
            }
        }

        File outputDirectory = requireFile(named, "output");
        int width = Integer.parseInt(named.getOrDefault("width", "2048"));
        int height = Integer.parseInt(named.getOrDefault("height", "2048"));

        try (HeadlessPipeline pipeline = HeadlessPipeline.open())
        {
            if (named.containsKey("vset"))
            {
                File supportingFilesDirectory = named.containsKey("supporting-dir")
                    ? new File(named.get("supporting-dir")) : outputDirectory;
                pipeline.loadFromVSETFile(requireFile(named, "vset"), supportingFilesDirectory);
            }
            else if (named.containsKey("metashape"))
            {
                MetashapeDocument document = new MetashapeDocument(requireFile(named, "metashape").getPath());
                MetashapeChunk chunk = named.containsKey("chunk")
                    ? selectChunk(document, named.get("chunk")) : document.getSelectedChunk();
                pipeline.loadFromMetashapeModel(chunk.getSelectedModel());
            }
            else
            {
                throw new IllegalArgumentException("Must specify either --vset or --metashape as the project to import.");
            }

            if (!flags.contains("skip-fit"))
            {
                SpecularFitSettings settings = new SpecularFitSettings(width, height);
                if (named.containsKey("basis-count"))
                {
                    settings.getSpecularBasisSettings().setBasisComplexity(Integer.parseInt(named.get("basis-count")));
                }
                pipeline.runSpecularFit(settings);
            }

            if (flags.contains("textures"))
            {
                pipeline.exportTextures(outputDirectory);
            }

            if (flags.contains("gltf"))
            {
                pipeline.exportGltf(outputDirectory, new ExportSettings());
            }
        }
    }

    private static MetashapeChunk selectChunk(MetashapeDocument document, String label)
    {
        for (MetashapeChunk chunk : document.getChunks())
        {
            if (chunk.getLabel().equals(label))
            {
                return chunk;
            }
        }

        throw new IllegalArgumentException("No chunk named '" + label + "' found in the Metashape project.");
    }

    private static File requireFile(Map<String, String> named, String key)
    {
        String value = named.get(key);
        if (value == null)
        {
            throw new IllegalArgumentException("Missing required argument: --" + key);
        }

        return new File(value);
    }
}
