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

package kintsugi3d.builder.fit.decomposition;

import kintsugi3d.builder.fit.ReflectanceData;
import kintsugi3d.gl.vecmath.DoubleVector3;
import kintsugi3d.optimization.LeastSquaresModel;

import java.util.function.IntFunction;

import static java.lang.Math.PI;

public class SpecularWeightModel implements LeastSquaresModel<ReflectanceData, DoubleVector3>
{
    private final SpecularDecomposition solution;

    /**
     *
     * @param solution
     */
    public SpecularWeightModel(SpecularDecomposition solution)
    {
        this.solution = solution;
    }

    @Override
    public boolean isValid(ReflectanceData sampleData, int systemIndex)
    {
        // Visibility test
        return sampleData.getVisibility(systemIndex) > 0;
    }

    @Override
    public double getSampleWeight(ReflectanceData sampleData, int systemIndex)
    {
        // Don't multiply by n dot l when optimizing reflectance (rather than radiance)
        return sampleData.getAdditionalWeight(systemIndex);
    }

    /**
     * The sum, over every geometrically valid light slot, of that light's incident radiance -- i.e. the
     * combined irradiance this pixel actually received. Under LIGHTS_PER_VIEW == 1 this is just that one
     * light's own incident radiance.
     */
    private static DoubleVector3 combinedIncidentRadiance(ReflectanceData sampleData, int systemIndex)
    {
        double red = 0.0;
        double green = 0.0;
        double blue = 0.0;

        for (int slot = 0; slot < sampleData.getLightsPerView(); slot++)
        {
            if (sampleData.isSlotValid(systemIndex, slot))
            {
                red += sampleData.getIncidentRadianceRed(systemIndex, slot);
                green += sampleData.getIncidentRadianceGreen(systemIndex, slot);
                blue += sampleData.getIncidentRadianceBlue(systemIndex, slot);
            }
        }

        return new DoubleVector3(red, green, blue);
    }

    @Override
    public DoubleVector3 getSamples(ReflectanceData sampleData, int systemIndex)
    {
        // Sampler (ground truth data). Under a single light this divides out that light's own incident
        // radiance, exactly as before. Under LIGHTS_PER_VIEW > 1 there is no single light's irradiance to
        // divide out any more (see estimateDiffuse.frag for why), so this instead divides by the COMBINED
        // irradiance from every valid light -- see getBasisFunctions() below, which predicts the matching
        // irradiance-weighted-average reflectance rather than a single light's reflectance.
        DoubleVector3 combined = combinedIncidentRadiance(sampleData, systemIndex);
        return new DoubleVector3(
            sampleData.getRed(systemIndex) / combined.x,
            sampleData.getGreen(systemIndex) / combined.y,
            sampleData.getBlue(systemIndex) / combined.z);
    }

    /**
     * Evaluates the basis BRDF (in reflectance space) for one light slot at one system index, i.e. the
     * per-light term that used to be the entire single-light getBasisFunctions() body.
     */
    private DoubleVector3 evaluateReflectance(int b, float halfwayIndex, float geomRatio)
    {
        int specularResolution = solution.getMaterialBasis().getSpecularResolution();
        double mExact = halfwayIndex * specularResolution;

        int m1 = (int) Math.floor(mExact);

        // This will run a lot of times so write out vector math operations to avoid unnecessary allocation.
        if (m1 < specularResolution)
        {
            int m2 = m1 + 1;
            double t = mExact - m1;

            return new DoubleVector3(
                solution.getDiffuseAlbedo(b).x / PI +
                    (solution.getMaterialBasis().evaluateSpecularRed(b, m1) * (1 - t)
                        + solution.getMaterialBasis().evaluateSpecularRed(b, m2) * t) * geomRatio,
                solution.getDiffuseAlbedo(b).y / PI +
                    (solution.getMaterialBasis().evaluateSpecularGreen(b, m1) * (1 - t)
                        + solution.getMaterialBasis().evaluateSpecularGreen(b, m2) * t) * geomRatio,
                solution.getDiffuseAlbedo(b).z / PI +
                    (solution.getMaterialBasis().evaluateSpecularBlue(b, m1) * (1 - t)
                        + solution.getMaterialBasis().evaluateSpecularBlue(b, m2) * t) * geomRatio);
        }
        else
        {
            return new DoubleVector3(
                solution.getDiffuseAlbedo(b).x / PI +
                    solution.getMaterialBasis().evaluateSpecularRed(b, specularResolution) * geomRatio,
                solution.getDiffuseAlbedo(b).y / PI +
                    solution.getMaterialBasis().evaluateSpecularGreen(b, specularResolution) * geomRatio,
                solution.getDiffuseAlbedo(b).z / PI +
                    solution.getMaterialBasis().evaluateSpecularBlue(b, specularResolution) * geomRatio);
        }
    }

    @Override
    public IntFunction<DoubleVector3> getBasisFunctions(ReflectanceData sampleData, int systemIndex)
    {
        // Precompute values that will be reused; captured by the lambda expression.
        int lightsPerView = sampleData.getLightsPerView();
        float[] halfwayIndex = new float[lightsPerView];
        float[] geomRatio = new float[lightsPerView];
        float[] incidentRadianceRed = new float[lightsPerView];
        float[] incidentRadianceGreen = new float[lightsPerView];
        float[] incidentRadianceBlue = new float[lightsPerView];
        boolean[] slotValid = new boolean[lightsPerView];

        for (int slot = 0; slot < lightsPerView; slot++)
        {
            slotValid[slot] = sampleData.isSlotValid(systemIndex, slot);
            if (slotValid[slot])
            {
                halfwayIndex[slot] = sampleData.getHalfwayIndex(systemIndex, slot);
                geomRatio[slot] = sampleData.getGeomRatio(systemIndex, slot);
                incidentRadianceRed[slot] = sampleData.getIncidentRadianceRed(systemIndex, slot);
                incidentRadianceGreen[slot] = sampleData.getIncidentRadianceGreen(systemIndex, slot);
                incidentRadianceBlue[slot] = sampleData.getIncidentRadianceBlue(systemIndex, slot);
            }
        }

        // Matches getSamples()'s division by the combined incident radiance: predicts the irradiance-weighted
        // AVERAGE of every valid light's own reflectance-space basis evaluation (each light's reflectance
        // weighted by its own share of the total irradiance this pixel received). This reduces exactly to
        // the original single-light reflectance prediction when LIGHTS_PER_VIEW == 1 (that one light's own
        // share of the combined irradiance is 1.0).
        DoubleVector3 combined = combinedIncidentRadiance(sampleData, systemIndex);

        return b ->
        {
            double sumRed = 0.0;
            double sumGreen = 0.0;
            double sumBlue = 0.0;

            for (int slot = 0; slot < lightsPerView; slot++)
            {
                if (slotValid[slot])
                {
                    DoubleVector3 reflectance = evaluateReflectance(b, halfwayIndex[slot], geomRatio[slot]);
                    sumRed += reflectance.x * incidentRadianceRed[slot];
                    sumGreen += reflectance.y * incidentRadianceGreen[slot];
                    sumBlue += reflectance.z * incidentRadianceBlue[slot];
                }
            }

            return new DoubleVector3(sumRed / combined.x, sumGreen / combined.y, sumBlue / combined.z);
        };
    }

    @Override
    public int getBasisFunctionCount()
    {
        return solution.getMaterialBasis().getMaterialCount();
    }

    @Override
    public double innerProduct(DoubleVector3 t1, DoubleVector3 t2)
    {
        return t1.dot(t2);
    }
}
