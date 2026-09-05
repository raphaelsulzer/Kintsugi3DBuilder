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

#line 14 4010

float calculateError(vec3 position, vec3 triangleNormal, vec3 estimatedNormal)
{
    float error = 0.0;

    for (int k = 0; k < CAMERA_POSE_COUNT; k++)
    {
        vec4 imgColor = getLinearColor(k);
        vec3 view = normalize(getViewVector(k, position));
        float triangleNDotV = max(0.0, dot(triangleNormal, view));
        float nDotV = max(0.0, dot(estimatedNormal, view));
        float roughness = texture(tex_roughness, fTexCoord)[0];

        // Under simultaneous multi-light illumination, compare radiance directly instead of dividing imgColor
        // by one light's irradiance to get "actual reflectance" (see estimateDiffuse.frag for the derivation).
        // The estimated side sums, over all LIGHTS_PER_VIEW lights, that light's predicted radiance
        // contribution. This reduces exactly to the original single-light formula when LIGHTS_PER_VIEW == 1.
        vec3 estimatedRadianceSum = vec3(0.0);
        vec3 totalIrradiance = vec3(0.0);
        vec3 weightAccum = vec3(0.0);
        bool anyValid = false;

        for (int slot = 0; slot < LIGHTS_PER_VIEW; slot++)
        {
            LightInfo lightInfo = getLightInfoForSlot(k, slot, position);
            vec3 light = lightInfo.normalizedDirection;
            vec3 halfway = normalize(light + view);
            float nDotH = max(0.0, dot(estimatedNormal, halfway));
            float nDotL = max(0.0, dot(estimatedNormal, light));

            // "Light intensity" is defined in such a way that we need to multiply by pi to be properly normalized.
            vec3 incidentRadiance = PI * lightInfo.attenuatedIntensity;
            totalIrradiance += incidentRadiance;

            if (nDotH > COSINE_CUTOFF && nDotL > COSINE_CUTOFF && nDotV > COSINE_CUTOFF)
            {
                float hDotV = max(0.0, dot(halfway, view));
                float maskingShadowing = geom(roughness, nDotH, nDotV, nDotL, hDotV);
                vec3 reflectanceEstimate = getBRDFEstimate(nDotH, maskingShadowing / (4 * nDotL * nDotV));

                // n dot l is already incorporated by virtue of the fact that radiance is being optimized, not reflectance.
                estimatedRadianceSum += reflectanceEstimate * nDotL * incidentRadiance;
                weightAccum += incidentRadiance * sqrt(max(0.0, 1.0 - nDotH * nDotH));
                anyValid = true;
            }
        }

        if (anyValid)
        {
            float totalIrradianceLum = max(1e-8, getLuminance(totalIrradiance));
            float weight = imgColor.a * triangleNDotV * (getLuminance(weightAccum) / totalIrradianceLum);

            vec3 diff = estimatedRadianceSum - imgColor.rgb;
            error += weight * dot(diff, diff);
        }
        else
        {
            // No light was in a geometrically valid configuration for this candidate normal for any of the
            // LIGHTS_PER_VIEW lights; fall back to penalizing based on the raw observed radiance magnitude
            // (the model would predict ~0 radiance here), steering away from normals that are implausible
            // for every light simultaneously.
            float weight = imgColor.a * triangleNDotV;
            error += sign(imgColor.a * triangleNDotV) * weight * dot(imgColor.rgb, imgColor.rgb);
        }
    }

    return error;
}
