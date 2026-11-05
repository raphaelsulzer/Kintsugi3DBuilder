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

#include "../specularFit.glsl"
#line 17 0

uniform vec3 reconstructionCameraPos;
uniform vec3 reconstructionLightPos[LIGHTS_PER_VIEW];
uniform vec3 reconstructionLightIntensity[LIGHTS_PER_VIEW];

layout(location = 0) out vec4 fragColor;

void main()
{
    float sqrtRoughness = texture(tex_roughness, fTexCoord)[0];
    float roughness = sqrtRoughness * sqrtRoughness;

    // Constant term for pseudo-translucency
    // Division by PI since it's fit on the same scale as diffuse
    vec3 constant = sRGBToLinear(getConstantTerm()) / PI;
    vec3 diffuseAlbedo = sRGBToLinear(texture(tex_diffuse, fTexCoord).rgb);

    // Under LIGHTS_PER_VIEW > 1, ImageReconstruction.java still multiplies this shader's output by a
    // single combined "incident radiance" value (now the SUM over all lights, from incidentRadiance.frag)
    // to get a comparable radiance -- so this outputs the matching irradiance-weighted AVERAGE reflectance
    // across all lights (same pattern as SpecularWeightModel.java's getSamples()/getBasisFunctions()),
    // rather than true summed radiance. This reduces exactly to the original single-light formula when
    // LIGHTS_PER_VIEW == 1.
    vec3 weightedReflectanceSum = vec3(0.0);
    vec3 totalIrradiance = vec3(0.0);

    for (int slot = 0; slot < LIGHTS_PER_VIEW; slot++)
    {
        // NOTE: argument order matches the original single-light call site exactly (camera position is
        // passed as calculateLightingParameters' first ["lightPos"] argument, light position as its second
        // ["cameraPos"] argument) -- preserved as-is rather than "fixed", since this is pre-existing
        // behavior unrelated to multi-light support and changing it would risk altering the single-light
        // case too.
        LightingParameters l = calculateLightingParameters(reconstructionCameraPos, reconstructionLightPos[slot]);

        vec3 lightDisplacement = reconstructionLightPos[slot] - getPosition();
        // "Light intensity" is defined in such a way that we need to multiply by pi to be properly normalized.
        vec3 incidentRadiance = reconstructionLightIntensity[slot] * PI / dot(lightDisplacement, lightDisplacement);
        totalIrradiance += incidentRadiance;

        vec3 reflectance;
        if (l.nDotL > 0.0 && l.nDotV > 0.0)
        {
            float maskingShadowing = geom(roughness, l.nDotH, l.nDotV, l.nDotL, l.hDotV);
            float geomRatio = maskingShadowing / (4 * l.nDotL * l.nDotV);
            vec3 brdf = diffuseAlbedo / PI + geomRatio * getMFDEstimate(l.nDotH);
            reflectance = l.nDotL * brdf + constant;
        }
        else if (l.nDotL > 0.0)
        {
            float geomRatio = 0.5 / (roughness * l.nDotL); // Limit as n dot v goes to zero.
            vec3 brdf = diffuseAlbedo / PI + geomRatio * getMFDEstimate(l.nDotH);
            reflectance = l.nDotL * brdf + constant;
        }
        else
        {
            // Limit as n dot l and n dot v both go to zero.
            vec3 mfd = getMFDEstimate(l.nDotH);
            reflectance = mfd * 0.5 / roughness + constant;
        }

        weightedReflectanceSum += reflectance * incidentRadiance;
    }

    // Gamma correction intentionally omitted for error calculation.
    fragColor = vec4(weightedReflectanceSum / max(vec3(1e-8), totalIrradiance), 1.0);
}
