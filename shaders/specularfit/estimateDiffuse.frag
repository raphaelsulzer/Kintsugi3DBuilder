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
#line 18 0

layout(location = 0) out vec4 diffuseOut;

void main()
{
    float sqrtRoughness = texture(tex_roughness, fTexCoord)[0];
    float roughness = sqrtRoughness * sqrtRoughness;

    vec3 position = getPosition();

    mat3 tangentToObject = constructTBNExact();
    vec3 triangleNormal = tangentToObject[2];

    vec2 fittedNormalXY = texture(tex_normal, fTexCoord).xy * 2 - vec2(1.0);
    vec3 fittedNormalTS = vec3(fittedNormalXY, sqrt(1 - dot(fittedNormalXY, fittedNormalXY)));
    vec3 fittedNormal = tangentToObject * fittedNormalTS;

    vec4 diffuseSum = vec4(0);

    for (int k = 0; k < CAMERA_POSE_COUNT; k++)
    {
        vec4 imgColor = getLinearColor(k);
        vec3 view = normalize(getViewVector(k, position));
        float triangleNDotV = max(0.0, dot(triangleNormal, view));

        if (imgColor.a > 0.0 && triangleNDotV > 0.0)
        {
            float nDotV = max(0.0, dot(fittedNormal, view));

            // Under simultaneous multi-light illumination, imgColor is the SUM of all LIGHTS_PER_VIEW lights'
            // contributions, so we can no longer recover a clean per-light "actual reflectance" by dividing
            // imgColor by one light's irradiance (that was only valid for a single light per view). Instead,
            // accumulate the known (specular-estimate-based) radiance contribution and the diffuse "sensitivity"
            // (the coefficient that multiplies diffuseAlbedo/PI in the forward radiance model) across all
            // lights, then solve for diffuseAlbedo directly from the forward model:
            //   imgColor = diffuseAlbedo/PI * sum_l(nDotL_l * incidentRadiance_l) + sum_l(specularEstimate_l * incidentRadiance_l)
            // This reduces exactly to the original single-light formula when LIGHTS_PER_VIEW == 1.
            vec3 specularRadianceSum = vec3(0.0);
            vec3 diffuseSensitivity = vec3(0.0);
            vec3 totalIrradiance = vec3(0.0);
            vec3 specularAvoidanceAccum = vec3(0.0);

            for (int slot = 0; slot < LIGHTS_PER_VIEW; slot++)
            {
                LightInfo lightInfo = getLightInfoForSlot(k, slot, position);
                vec3 light = lightInfo.normalizedDirection;
                vec3 halfway = normalize(light + view);
                float nDotH = max(0.0, dot(fittedNormal, halfway));
                float nDotL = max(0.0, dot(fittedNormal, light));

                // "Light intensity" is defined in such a way that we need to multiply by pi to be properly normalized.
                vec3 incidentRadiance = PI * lightInfo.attenuatedIntensity;
                totalIrradiance += incidentRadiance;

                if (nDotH > 0.0 && nDotL > 0.0 && nDotV > 0.0)
                {
                    float hDotV = max(0.0, dot(halfway, view));
                    float maskingShadowing = geom(roughness, nDotH, nDotV, nDotL, hDotV);
                    vec3 specularEstimate = getMFDEstimate(nDotH) * maskingShadowing / (4 * nDotV);

                    specularRadianceSum += specularEstimate * incidentRadiance;
                    diffuseSensitivity += nDotL * incidentRadiance;
                    // Avoid overfitting to specular dominated samples (irradiance-weighted across lights).
                    specularAvoidanceAccum += incidentRadiance * sqrt(max(0.0, 1.0 - nDotH * nDotH));
                }
            }

            // Reduce the per-channel light-geometry aggregates to scalars via luminance, since (as in the
            // original single-light formula) nDotL/weight are meant to be achromatic geometric quantities,
            // shared across color channels -- only diffuseAlbedoEstimate itself stays per-channel.
            float totalIrradianceLum = getLuminance(totalIrradiance);
            if (totalIrradianceLum > 0.0 && getLuminance(diffuseSensitivity) > 0.0)
            {
                vec3 safeSensitivity = max(vec3(1e-8), diffuseSensitivity);
                vec3 diffuseAlbedoEstimate = PI * (imgColor.rgb - specularRadianceSum) / safeSensitivity; // could be negative
                float safeIrradianceLum = max(1e-8, totalIrradianceLum);
                float nDotLAgg = getLuminance(diffuseSensitivity) / safeIrradianceLum;
                float weight = getLuminance(specularAvoidanceAccum) / safeIrradianceLum;

                vec3 diffuse = diffuseAlbedoEstimate * nDotLAgg;
                diffuseSum += vec4(weight * diffuse * nDotLAgg * triangleNDotV, weight * nDotLAgg * nDotLAgg * triangleNDotV);
            }
        }
    }

    diffuseOut = vec4(linearToSRGB(max(vec3(0), diffuseSum.rgb / max(1.0, diffuseSum.a))), min(1.0, diffuseSum.a));
}
