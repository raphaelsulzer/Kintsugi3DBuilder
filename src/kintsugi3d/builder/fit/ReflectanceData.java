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

import kintsugi3d.util.ColorList;

/**
 * Class that maps output from fragment shader to the expected inputs to the fitting algorithm.
 *
 * <p>Under LIGHTS_PER_VIEW &gt; 1 (multiple simultaneous lights per view, e.g. a triple-flash rig), the
 * shader (extractReflectance.frag) emits one shared observed radiance/visibility for the whole pixel, plus
 * one halfway-index/geomRatio/weight/validity sample PER LIGHT SLOT -- since each light has its own
 * half-vector and therefore its own domain position in the specular basis, but they all contribute to the
 * SAME observed radiance value. The per-slot "weight" channel is (by construction, see
 * extractReflectance.frag) identical across all slots for a given pixel -- it is a single value shared
 * across lights, not a per-light quantity -- so {@link #getAdditionalWeight(int)} reads only slot 0.
 */
public class ReflectanceData
{
    /**
     * Color and visibility components of the samples (shared across all light slots).
     */
    private final ColorList colorAndVisibility;

    /**
     * Halfway angle, geometric factor, shared weight, and validity flag for the samples, one ColorList per
     * light slot (length LIGHTS_PER_VIEW).
     */
    private final ColorList[] halfwayGeomWeightValidityPerSlot;

    /**
     * Each light's incident radiance (PI * attenuatedIntensity) at this pixel, one ColorList per light slot
     * (length LIGHTS_PER_VIEW), needed by {@link kintsugi3d.builder.fit.decomposition.SpecularWeightModel}
     * to convert a per-light reflectance-space basis evaluation into that light's contribution to the
     * shared observed radiance.
     */
    private final ColorList[] incidentRadiancePerSlot;

    @SuppressWarnings("AssignmentOrReturnOfFieldWithMutableType")
    public ReflectanceData(ColorList colorAndVisibility, ColorList[] halfwayGeomWeightValidityPerSlot,
        ColorList[] incidentRadiancePerSlot)
    {
        this.colorAndVisibility = colorAndVisibility;
        this.halfwayGeomWeightValidityPerSlot = halfwayGeomWeightValidityPerSlot;
        this.incidentRadiancePerSlot = incidentRadiancePerSlot;
    }

    public int getLightsPerView()
    {
        return halfwayGeomWeightValidityPerSlot.length;
    }

    public float getRed(int p)
    {
        return colorAndVisibility.get(p, 0);
    }

    public float getGreen(int p)
    {
        return colorAndVisibility.get(p, 1);
    }

    public float getBlue(int p)
    {
        return colorAndVisibility.get(p, 2);
    }

    public float getVisibility(int p)
    {
        return colorAndVisibility.get(p, 3);
    }

    public float getHalfwayIndex(int p, int slot)
    {
        return halfwayGeomWeightValidityPerSlot[slot].get(p, 0);
    }

    public float getGeomRatio(int p, int slot)
    {
        return halfwayGeomWeightValidityPerSlot[slot].get(p, 1);
    }

    /**
     * The shared per-pixel sample weight. Identical across all light slots by construction (see
     * extractReflectance.frag), so only slot 0 needs to be read.
     */
    public float getAdditionalWeight(int p)
    {
        return halfwayGeomWeightValidityPerSlot[0].get(p, 2);
    }

    public boolean isSlotValid(int p, int slot)
    {
        return halfwayGeomWeightValidityPerSlot[slot].get(p, 3) > 0;
    }

    public float getIncidentRadianceRed(int p, int slot)
    {
        return incidentRadiancePerSlot[slot].get(p, 0);
    }

    public float getIncidentRadianceGreen(int p, int slot)
    {
        return incidentRadiancePerSlot[slot].get(p, 1);
    }

    public float getIncidentRadianceBlue(int p, int slot)
    {
        return incidentRadiancePerSlot[slot].get(p, 2);
    }

    public int size()
    {
        return colorAndVisibility.size();
    }
}
