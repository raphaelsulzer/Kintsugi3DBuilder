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

#ifndef COLOR_APPEARANCE_GLSL
#define COLOR_APPEARANCE_GLSL

#include "../common/usegeom.glsl"
#include "linearize.glsl"
#include "../common/extractcomponent.glsl"

#line 21 1000

#ifndef PI
#define PI 3.1415926535897932384626433832795 // For convenience
#endif

#ifndef CAMERA_POSE_COUNT
#define CAMERA_POSE_COUNT 1024
#endif

#define CAMERA_POSE_COUNT_DIV_4 ((CAMERA_POSE_COUNT + 3) / 4)

#ifndef LIGHT_COUNT
#define LIGHT_COUNT 1024
#endif

#ifndef VIEW_COUNT
#define VIEW_COUNT CAMERA_POSE_COUNT
#endif

#define VIEW_COUNT_DIV_4 ((VIEW_COUNT + 3) / 4)

#ifndef USE_VIEW_INDICES
#define USE_VIEW_INDICES 0
#endif

#if USE_VIEW_INDICES
layout(std140) uniform ViewIndices
{
    ivec4 viewIndices[VIEW_COUNT_DIV_4];
};
#endif

int getViewIndex(int virtualIndex)
{
#if USE_VIEW_INDICES
    return extractComponentByIndex(viewIndices[virtualIndex/4], virtualIndex%4);
#else
    return virtualIndex;
#endif
}

#ifndef INFINITE_LIGHT_SOURCES
#define INFINITE_LIGHT_SOURCES 0
#endif

#ifndef FLATFIELD_CORRECTED
#define FLATFIELD_CORRECTED 0
#endif

layout(std140) uniform CameraWeights
{
    vec4 cameraWeights[CAMERA_POSE_COUNT_DIV_4];
};

layout(std140) uniform CameraPoses
{
    mat4 cameraPoses[CAMERA_POSE_COUNT];
};

layout(std140) uniform LightPositions
{
    vec4 lightPositions[LIGHT_COUNT];
};

layout(std140) uniform LightIntensities
{
    vec4 lightIntensities[LIGHT_COUNT];
};

layout(std140) uniform LightIndices
{
    ivec4 lightIndices[CAMERA_POSE_COUNT_DIV_4];
};

struct LightInfo
{
    vec3 attenuatedIntensity;
    vec3 normalizedDirection;
};

mat4 getCameraPose(int virtualIndex)
{
    return cameraPoses[getViewIndex(virtualIndex)];
}

int getLightIndex(int virtualIndex)
{
    int viewIndex = getViewIndex(virtualIndex);
    return extractComponentByIndex(lightIndices[viewIndex/4], viewIndex%4);
}

float getCameraWeight(int virtualIndex)
{
    int viewIndex = getViewIndex(virtualIndex);
    return extractComponentByIndex(cameraWeights[viewIndex/4], viewIndex%4);
}

vec3 getViewVector(int virtualIndex, vec3 position)
{
    int viewIndex = getViewIndex(virtualIndex);
    return transpose(mat3(cameraPoses[viewIndex])) * -cameraPoses[viewIndex][3].xyz - position;
}

vec3 getLightVector(int virtualIndex, vec3 position)
{
    int viewIndex = getViewIndex(virtualIndex);
    return transpose(mat3(cameraPoses[viewIndex])) *
        (lightPositions[getLightIndex(virtualIndex)].xyz - cameraPoses[viewIndex][3].xyz) - position;
}

vec3 getViewVector(int virtualIndex)
{
    return getViewVector(virtualIndex, getPosition());
}

vec3 getLightVector(int virtualIndex)
{
    return getLightVector(virtualIndex, getPosition());
}

vec3 getLightIntensity(int virtualIndex)
{
    return lightIntensities[getLightIndex(virtualIndex)].rgb;
}

LightInfo getLightInfo(int virtualIndex)
{
    LightInfo result;

#if FLATFIELD_CORRECTED && !INFINITE_LIGHT_SOURCES
    // Just use normalize() since we won't actually be needing a true lightDistSquared.
    result.normalizedDirection = normalize(getLightVector(virtualIndex));

    // Tone calibration assumes the tone patches are square with the camera, essentially on camera axis,
    // at the same distance as the closest point on the object.
    // Flat field correction means that a white card placed at that same distance, with the same tone as any particular
    // patch on the calibration chart, will after correction have the same values everywhere
    // So after flat field correction and tone calibration together, we should get an exact albedo measurement
    // from the RGB pixel values at that specific distance.
    //
    // Flat field correction does not work perfectly if the uniform card is not at the same distance (particularly if closer)
    // -- i.e. in an extreme example where the uniform card is much closer to the light there will be a hot spot in the center
    // that is not present in the other photos, which will become a dark spot after flat field correction.
    // So let's assume that the flat field card is at the same distance as the tone calibration card.
    //
    // Flatfield gain = average(flatfield image pixels) / (specific flatfield image pixel)
    // Using inverse-square attenuation, this comes out to average(1 / flatfield_light_dist^2) / (1 / flatfield_light_dist[x,y]^2)
    //  = flatfield_light_dist[x,y]^2 * average(1 / flatfield_light_dist^2)
    //
    // Therefore, incident radiance = light_power / light_dist^2 * flatfield_light_dist[x,y]^2 * average(1 / flatfield_light_dist^2)
    //
    // For now, to keep things simple let's just approximate that:
    // flatfield_light_dist[x,y]^2 / light_dist^2 = flatfield_camera_axis_dist^2 / surface_camera_axis_dist^2
    // and that flatfield_camera_axis_dist^2 * average(1 / flatfield_light_dist^2) is approximately 1.0
    // With those approximations, incident radiance is just light_power / surface_camera_axis_dist^2

    // Transform to camera space and just grab the z-axis.
    float cameraAxisDist = (cameraPoses[getViewIndex(virtualIndex)] * vec4(getPosition(), 1.0)).z;
    float lightDistSquared = dot(cameraAxisDist, cameraAxisDist);
#else
    vec3 unnormalizedDirection = getLightVector(virtualIndex);
    float lightDistSquared = dot(unnormalizedDirection, unnormalizedDirection);
    result.normalizedDirection = unnormalizedDirection * inversesqrt(lightDistSquared);
#endif

    result.attenuatedIntensity = getLightIntensity(virtualIndex);
#if !INFINITE_LIGHT_SOURCES
    result.attenuatedIntensity /= lightDistSquared;
#endif

    return result;
}

vec4 getColor(int virtualIndex); // Defined by imgspace.glsl or texspace_crop.glsl

vec4 getLinearColor(int virtualIndex)
{
    return linearizeColor(getColor(virtualIndex));
}

// ---------------------------------------------------------------------------
// Multi-light-per-view support (triple-flash rig: one center + two side lights
// captured in a single simultaneous exposure per view).
//
// Each view's lights are assumed to occupy LIGHTS_PER_VIEW consecutive slots in
// the LightPositions/LightIntensities arrays, starting at getLightIndex(virtualIndex).
// The functions below are explicit-index variants of the ones above, used to
// address one specific light out of that group instead of the single light that
// getLightInfo()/getLightVector()/getLightIntensity() assume.
//
// NOTE: these do NOT implement the FLATFIELD_CORRECTED branch that getLightInfo()
// has -- flat-field tone calibration was defined and calibrated for a single
// on-axis light and does not have a well-defined per-light meaning for an
// off-axis side light, so multi-light fitting is only supported without flat-field
// correction.
// ---------------------------------------------------------------------------

#ifndef LIGHTS_PER_VIEW
#define LIGHTS_PER_VIEW 1
#endif

int getLightIndexForSlot(int virtualIndex, int slot)
{
    return getLightIndex(virtualIndex) + slot;
}

vec3 getLightVectorAtIndex(int virtualIndex, int lightIndex, vec3 position)
{
    int viewIndex = getViewIndex(virtualIndex);
    return transpose(mat3(cameraPoses[viewIndex])) *
        (lightPositions[lightIndex].xyz - cameraPoses[viewIndex][3].xyz) - position;
}

vec3 getLightIntensityAtIndex(int lightIndex)
{
    return lightIntensities[lightIndex].rgb;
}

LightInfo getLightInfoAtIndex(int virtualIndex, int lightIndex, vec3 position)
{
    LightInfo result;

    vec3 unnormalizedDirection = getLightVectorAtIndex(virtualIndex, lightIndex, position);
    float lightDistSquared = dot(unnormalizedDirection, unnormalizedDirection);
    result.normalizedDirection = unnormalizedDirection * inversesqrt(lightDistSquared);

    result.attenuatedIntensity = getLightIntensityAtIndex(lightIndex);
#if !INFINITE_LIGHT_SOURCES
    result.attenuatedIntensity /= lightDistSquared;
#endif

    return result;
}

// Convenience wrapper: address a light by its slot (0..LIGHTS_PER_VIEW-1) within
// the current view's group of lights, rather than by its raw index into the
// LightPositions/LightIntensities arrays.
LightInfo getLightInfoForSlot(int virtualIndex, int slot, vec3 position)
{
    return getLightInfoAtIndex(virtualIndex, getLightIndexForSlot(virtualIndex, slot), position);
}

// Sum of all LIGHTS_PER_VIEW lights' attenuated intensity, treated as if they were
// a single light from one direction. This is only a valid approximation for coarse
// forward-comparison uses (e.g. average.frag's initial reflectance clustering, where
// the divide-by-intensity is already just a rough proxy and not a true per-half-vector
// reflectance recovery). It must NOT be used anywhere that needs each light's own
// direction and half-vector individually (extractReflectance.frag and the
// normal/diffuse/error shaders that derive "actual reflectance" from it) -- those
// need getLightInfoForSlot() called once per slot and summed in radiance space instead.
vec3 getCombinedLightIntensity(int virtualIndex, vec3 position)
{
    vec3 total = vec3(0.0);
    for (int slot = 0; slot < LIGHTS_PER_VIEW; slot++)
    {
        total += getLightInfoForSlot(virtualIndex, slot, position).attenuatedIntensity;
    }
    return total;
}

#endif // COLOR_APPEARANCE_GLSL
