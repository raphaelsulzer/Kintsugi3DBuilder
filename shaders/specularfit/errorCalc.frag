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

uniform bool sRGB;

layout(location = 0) out vec4 errorOut;

void main()
{
    vec2 sqrtRoughness_Mask = texture(tex_roughness, fTexCoord).ra;
    float filteredMask = sqrtRoughness_Mask[1];

    float roughness = sqrtRoughness_Mask[0] * sqrtRoughness_Mask[0];
    vec3 diffuseColor = getDiffuseEstimate();

    vec3 position = getPosition();

    mat3 tangentToObject = constructTBNExact();
    vec3 triangleNormal = tangentToObject[2];

    vec2 fittedNormalXY = texture(tex_normal, fTexCoord).xy * 2 - vec2(1.0);
    vec3 fittedNormalTS = vec3(fittedNormalXY, sqrt(1 - dot(fittedNormalXY, fittedNormalXY)));
    vec3 fittedNormal = tangentToObject * fittedNormalTS;

    float error = 0.0;
    float validCount = 0;

    for (int k = 0; k < CAMERA_POSE_COUNT; k++)
    {
        vec4 imgColor = getLinearColor(k);
        vec3 view = normalize(getViewVector(k, position));
        float triangleNDotV = max(0.0, dot(triangleNormal, view));
        float nDotV = max(0.0, dot(fittedNormal, view));

        // Under simultaneous multi-light illumination, compare radiance directly instead of dividing imgColor
        // by one light's irradiance to get "actual reflectance" (see estimateDiffuse.frag for the derivation).
        // The estimated side is the sum, over all LIGHTS_PER_VIEW lights, of that light's predicted radiance
        // contribution. This reduces exactly to the original single-light formula when LIGHTS_PER_VIEW == 1.
        vec3 estimatedRadianceSum = vec3(0.0);
        bool anyValid = false;

        for (int slot = 0; slot < LIGHTS_PER_VIEW; slot++)
        {
            LightInfo lightInfo = getLightInfoForSlot(k, slot, position);
            vec3 light = lightInfo.normalizedDirection;
            vec3 halfway = normalize(light + view);
            float nDotH = max(0.0, dot(fittedNormal, halfway));
            float nDotL = max(0.0, dot(fittedNormal, light));

            // "Light intensity" is defined in such a way that we need to multiply by pi to be properly normalized.
            vec3 incidentRadiance = PI * lightInfo.attenuatedIntensity;

            if (nDotH > 0.0 && nDotL > 0.0 && nDotV > 0.0 && filteredMask > 0.0)
            {
                float hDotV = max(0.0, dot(halfway, view));
                float maskingShadowing = geom(roughness, nDotH, nDotV, nDotL, hDotV);
                vec3 specular = getMFDEstimate(nDotH) * maskingShadowing / (4 * nDotV);
                vec3 reflectanceEstimateTimesNDotL = diffuseColor * nDotL / PI + specular;

                estimatedRadianceSum += reflectanceEstimateTimesNDotL * incidentRadiance;
                anyValid = true;
            }
        }

        float weight = imgColor.a * triangleNDotV;

        vec3 actualRadiance = imgColor.rgb;
        if (sRGB)
        {
            actualRadiance = linearToSRGB(actualRadiance);
        }

        if (anyValid)
        {
            vec3 estimatedRadiance = estimatedRadianceSum;
            if (sRGB)
            {
                estimatedRadiance = linearToSRGB(estimatedRadiance);
            }

            vec3 diff = actualRadiance - estimatedRadiance;
            error += weight * dot(diff, diff);
        }
        else
        {
            error += weight * dot(actualRadiance, actualRadiance);
        }

        validCount += 3 * weight;
    }

    errorOut = vec4(vec3(error), validCount);
}
