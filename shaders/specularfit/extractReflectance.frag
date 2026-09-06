#version 330

/*
 * Copyright (c) 2019 - 2023 Seth Berrier, Michael Tetzlaff, Jacob Buelow, Luke Denney
 * Copyright (c) 2019 The Regents of the University of Minnesota
 *
 * Licensed under GPLv3
 * ( http://www.gnu.org/licenses/gpl-3.0.html )
 *
 * This code is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 * This code is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 *
 */

#include "specularFit.glsl"
#include <colorappearance/colorappearance_multi_as_single.glsl>
#line 18 0

// Under a single light per view, "reflectance" could be recovered by dividing the observed radiance by
// that light's irradiance. Under LIGHTS_PER_VIEW simultaneous lights, imgColor is the SUM of all of their
// contributions, so no such per-light division is possible any more -- there is no single "reflectance
// value at one half-vector" to output. Instead, this shader now outputs the raw (undivided) observed
// radiance once per pixel, plus one halfway/geom/weight sample PER LIGHT (each light has its own half-vector
// and therefore its own domain position in the specular basis). The basis-shape extraction on the Java side
// (kintsugi3d.optimization.function, driven by ReflectanceMatrixBuilder) combines these into the full
// multi-light least-squares system: same-light diagonal terms reuse the existing single-light sweep (run
// once per light slot), while cross-light terms are handled by a dedicated accumulator, since two
// independently-varying half-angle positions per sample cannot be handled by a single 1D sorted sweep.
// This reduces exactly to the original single-light behavior when LIGHTS_PER_VIEW == 1.
layout(location = 0) out vec4 observedRadiance_visibility;
layout(location = 1) out vec4 halfway_geom_weight[LIGHTS_PER_VIEW];
// Per-light incident radiance (PI * attenuatedIntensity), needed by the per-texel weight-fitting step
// (SpecularWeightModel) to convert each light's basis-predicted reflectance into that light's own
// contribution to the shared observed radiance -- geomRatio alone (unlike in the single-light case) is no
// longer enough once "reflectance" can no longer be recovered by dividing out one light's irradiance.
layout(location = 1 + LIGHTS_PER_VIEW) out vec4 incidentRadiance[LIGHTS_PER_VIEW];

void main()
{
    vec3 position = getPosition();
    vec4 imgColor = getLinearColor();
    vec3 view = normalize(getViewVector(position));

    mat3 tangentToObject = constructTBNExact();
    vec3 triangleNormal = tangentToObject[2];

    vec2 normalDirXY = texture(tex_normal, fTexCoord).xy * 2 - vec2(1.0);
    vec3 normalDirTS = vec3(normalDirXY, sqrt(1 - dot(normalDirXY, normalDirXY)));
    vec3 normal = tangentToObject * normalDirTS;

    float nDotV = max(0.0, dot(normal, view));
    float triangleNDotV = max(0.0, dot(triangleNormal, view));
    float roughness = texture(tex_roughness, fTexCoord)[0];

    // First pass: per-light geometry and validity. The Java-side basis-shape-extraction accumulator (see
    // ReflectanceMatrixBuilder) needs a single SHARED sample weight for this pixel (one least-squares
    // residual per pixel, combining all lights' contributions) rather than each light's own local weight --
    // otherwise the diagonal-term derivation (running the existing single-light sweep once per light slot)
    // would not correctly reduce to the true joint objective. So the anti-overfitting weight is computed per
    // light here, then averaged in a second pass and written identically into every valid slot below. This
    // reduces exactly to the original single-light formula when LIGHTS_PER_VIEW == 1.
    float halfwayIndices[LIGHTS_PER_VIEW];
    float geomRatios[LIGHTS_PER_VIEW];
    float localWeights[LIGHTS_PER_VIEW];
    vec3 incidentRadiances[LIGHTS_PER_VIEW];
    bool valid[LIGHTS_PER_VIEW];
    bool anyValid = false;

    for (int slot = 0; slot < LIGHTS_PER_VIEW; slot++)
    {
        LightInfo lightInfo = getLightInfoForSlot(slot, position);
        vec3 light = lightInfo.normalizedDirection;
        vec3 halfway = normalize(light + view);
        float nDotL = max(0.0, dot(normal, light));
        float nDotH = max(0.0, dot(normal, halfway));

        if (nDotH > COSINE_CUTOFF && nDotL > COSINE_CUTOFF && nDotV > COSINE_CUTOFF && triangleNDotV > COSINE_CUTOFF)
        {
            float hDotV = max(0.0, dot(halfway, view));
            float maskingShadowing = geom(roughness, nDotH, nDotV, nDotL, hDotV);

            // Halfway component should be 1.0 when the angle is 60 degrees, or pi/3.
            halfwayIndices[slot] = sqrt(max(0.0, acos(min(1.0, nDotH)) * 3.0 / PI));
            geomRatios[slot] = maskingShadowing / (4 * nDotL * nDotV);
            // n.v accounts for fitting in texture space rather than image space (more samples near grazing angles)
            // (n.l)^2 accounts for fitting reflectance rather than radiance
            // sin(theta_h) prevents bias towards specular (from Nam et al.)
            localWeights[slot] = imgColor.a * triangleNDotV * nDotL * nDotL * sqrt(max(0, 1 - nDotH * nDotH));
            // "Light intensity" is defined in such a way that we need to multiply by pi to be properly normalized.
            incidentRadiances[slot] = PI * lightInfo.attenuatedIntensity;
            valid[slot] = true;
            anyValid = true;
        }
        else
        {
            // This light slot contributes nothing for this pixel (e.g. self-shadowed or grazing), but other
            // slots may still be valid.
            halfwayIndices[slot] = 0.0;
            geomRatios[slot] = 0.0;
            localWeights[slot] = 0.0;
            incidentRadiances[slot] = vec3(0.0);
            valid[slot] = false;
        }
    }

    if (!anyValid)
    {
        discard;
        return;
    }

    // Second pass: shared weight (divided by LIGHTS_PER_VIEW, not the valid count, so a pixel with fewer
    // valid lights is deliberately treated as less reliable overall) and output.
    float sharedWeight = 0.0;
    for (int slot = 0; slot < LIGHTS_PER_VIEW; slot++)
    {
        sharedWeight += localWeights[slot];
    }
    sharedWeight /= float(LIGHTS_PER_VIEW);

    for (int slot = 0; slot < LIGHTS_PER_VIEW; slot++)
    {
        halfway_geom_weight[slot] = valid[slot]
            ? vec4(halfwayIndices[slot], geomRatios[slot], sharedWeight, 1.0) // 1.0: validity flag for this slot
            : vec4(0.0);
        incidentRadiance[slot] = vec4(incidentRadiances[slot], valid[slot] ? 1.0 : 0.0);
    }

    observedRadiance_visibility = vec4(imgColor.rgb, imgColor.a);
}
