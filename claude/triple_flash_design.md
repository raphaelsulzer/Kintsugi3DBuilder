# Triple-flash BRDF fitting: design notes

Worktrees for this task: `Kintsugi3DBuilder-triple-flash` (branch `triple-flash`, off `dev`)
and `threedo-triple-flash` (branch `triple-flash`, off `main`). Goal: fit a Kintsugi
material to `/data/shared/mug_020926_sfm_ImagesFlashTriple/sfm_images` (triple simultaneous
flash: one on-axis center flash plus two off-axis flashes roughly 1m left/right of center),
using the sfm_images (not the separate kin_images) and a genuine 3-simultaneous-light BRDF
fit (full 3-light sum in the fit, not a single-effective-combined-light approximation).

## Why this isn't a drop-in config change

Kintsugi's `ViewSet`/shader light model (`shaders/colorappearance/colorappearance.glsl`)
assumes exactly one light per view (`getLightIndex(virtualIndex)` maps a view to a single
index into the `LightPositions`/`LightIntensities` arrays). The specular-fit shaders derive
an "actual reflectance" sample from each pixel by dividing the raw observed image color by
that one light's irradiance:

```
actualReflectanceTimesNDotL = imgColor.rgb / (PI * lightInfo.attenuatedIntensity)
```

This division is only valid when exactly one light illuminates the surface in that photo.
Under triple-flash, `imgColor` is the sum of three simultaneous lights' contributions, so
dividing the total by one light's irradiance does not recover anything meaningful.

## Shader-by-shader classification

All shaders under `shaders/specularfit/` (and `colorappearance.glsl`) were read in full and
classified by how they use light information:

- **Tier 1 (trivial -- forward comparison, no per-light division):**
  `average.frag`. Used only for coarse initial clustering; generalized by summing all
  lights' attenuated intensity as one (`getCombinedLightIntensity`), which is an explicitly
  documented approximation acceptable only here.

- **Tier 2 (division-based reflectance recovery -- needed restructuring to radiance-space
  comparison):** `estimateDiffuse.frag`, `estimateDiffuseTranslucent.frag`,
  `estimateNormals.frag`, `errorCalc.frag`, `finalErrorCalc.frag`, `ggxErrorCalc.frag`,
  `normalError.glsl` (`calculateError`). All of these compute
  `imgColor.rgb / incidentRadiance` from ONE light and then either solve a small linear
  system (diffuse albedo, in `estimateDiffuse*.frag`) or compare it against a reflectance
  estimate to form an error/gradient (`errorCalc*.frag`, `estimateNormals.frag`,
  `normalError.glsl`). **This is not confined to `extractReflectance.frag`** -- it is the
  dominant pattern across almost the entire specular-fit shader family, because "reflectance"
  is fundamentally computed the same way everywhere: `radiance / irradiance`, which only
  makes sense for one active light.

  Fix pattern (uniform across all 7 files): stop dividing `imgColor` by a single light's
  irradiance. Instead keep `imgColor.rgb` undivided as the "actual" quantity, and build the
  "estimate" side as a sum over all `LIGHTS_PER_VIEW` lights of
  `reflectanceEstimate_l * nDotL_l * incidentRadiance_l`, then compare/solve directly in
  **radiance space**. This reduces exactly to the original single-light formula when
  `LIGHTS_PER_VIEW == 1`, and required no new data structures -- just restructuring each
  file's inner loop to loop over lights before comparing.

  For `estimateDiffuse.frag`/`estimateDiffuseTranslucent.frag` specifically, solving for the
  unknown diffuse albedo directly from the forward model gives:
  ```
  diffuseAlbedo = PI * (imgColor.rgb - sum_l(specularEstimate_l * incidentRadiance_l))
                       / sum_l(nDotL_l * incidentRadiance_l)
  ```
  The geometric "reliability" quantities (nDotL, the anti-overfitting weight) that were
  scalars in the single-light version are reduced back to scalars via `getLuminance()` on
  the per-channel aggregates (irradiance-weighted average across lights), preserving the
  achromatic-geometry convention the rest of the file uses.

  For `estimateNormals.frag`'s Levenberg-Marquardt Jacobian, each light's per-channel
  `fullGradient` (gradient of reflectance*nDotL w.r.t. the 2D tangent-space normal offset)
  is scaled by that light's own `incidentRadiance` (converting it to a radiance gradient),
  then summed across lights before forming `mJTJ`/`vJTb` against the radiance-space residual
  `imgColor.rgb - estimatedRadianceSum`. All status quo per-light validity checks
  (`nDotH > COSINE_CUTOFF && nDotL > COSINE_CUTOFF`) became per-light `continue` guards
  inside a light loop instead of a single per-view `if`.

  All 7 files have been edited. `colorappearance.glsl` and
  `colorappearance_multi_as_single.glsl` gained a `LIGHTS_PER_VIEW` define (default 1) and
  explicit-index/slot APIs: `getLightIndexForSlot`, `getLightVectorAtIndex`,
  `getLightIntensityAtIndex`, `getLightInfoAtIndex`, `getLightInfoForSlot` (both the
  `(virtualIndex, slot, position)` and single-view `(slot, position)` forms), and
  `getCombinedLightIntensity` (Tier-1-only approximation, explicitly documented as invalid
  for anything that needs per-light half-vectors). None of the new functions implement the
  `FLATFIELD_CORRECTED` branch of `getLightInfo()` -- flat-field tone calibration doesn't
  have a well-defined per-light meaning for an off-axis side light, so multi-light fitting
  is only supported without flat-field correction.

- **Tier 3 (hard -- genuinely incompatible with per-sample division, needs new math):**
  `extractReflectance.frag` feeding `MatrixBuilder`/`AbstractBasisFunctions`
  (`src/kintsugi3d/optimization/function/`) for basis-shape extraction (discovering the
  tabulated `MaterialBasis.evaluateSpecularRed/Green/Blue(b, m)` curves), and
  `SpecularWeightModel.java` (per-texel weight fitting onto those bases).

## Why Tier 3 needs a full mathematical rederivation (user's explicit choice, not the
## EM-approximation alternative)

`extractReflectance.frag` emits, per pixel/view sample, ONE scalar domain position (a
"halfway index" `m`, from `sqrt(acos(nDotH) * 3 / PI)`) plus a reflectance value recovered
by division. `MatrixBuilder.build()` sorts all samples by that single scalar and sweeps
through them once, using `AbstractBasisFunctions.contributeToFittingSystem()` to update
running cumulative sums that only need local correction at "bin" boundaries -- this is what
makes an otherwise O(samples x functionCount^2) matrix-assembly problem run in roughly
O(samples + functionCount).

Under 3 simultaneous lights, one physical pixel observation corresponds to **3 different
half-angle domain positions at once** (one per light), all contributing additively to the
SAME single observed radiance value. Deriving the correct normal-equations contributions:

- **Same-light (diagonal) terms and the RHS vector reduce to the existing 1D sweep.**
  Expanding the least-squares system's `A^T A` and `A^T y` over 3 lights, the terms where
  both factors come from the SAME light (3 of them: light0-light0, light1-light1,
  light2-light2) are mathematically identical in form to the original single-light
  derivation -- just using that light's own domain positions and known factors. Likewise,
  `A^T y`'s entries are a plain sum over lights for a fixed basis-function index (no cross
  terms). **Both can be computed by calling the existing `MatrixBuilder`/
  `AbstractBasisFunctions` sweep once per light (3 times total), each accumulating into the
  same shared `MatrixSystem` output** -- no new code needed for these parts.

- **Cross-light (off-diagonal) terms in the LHS (A^T A) matrix need new code.** The 3
  unordered light pairs (0-1, 0-2, 1-2) each contribute terms of the form
  `f_k1(m_light_a) * f_k2(m_light_b)` for a SAMPLE-DEPENDENT PAIR of domain positions that
  vary independently per sample. There is no single sort order that keeps both `m_light_a`
  and `m_light_b` monotonic simultaneously across samples, so the existing 1D
  cumulative-sum trick does not generalize -- this genuinely requires a new **2D
  domain-pair accumulation structure** (e.g., a 2D range/prefix-sum structure over
  `(m_light_a, m_light_b)` pairs) to avoid an O(samples x functionCount^2) brute-force cost
  per cross-light pair. This is real, novel numerical/algorithms work with no existing
  template in the codebase to build from -- the user was told this explicitly (framed as
  "full mathematical rederivation" vs. an EM-style per-iteration apportionment
  approximation that would have reused the existing 1D code unchanged) and chose the full
  rederivation.

**Status as of this writing: Tier 1 and Tier 2 are fully implemented (see file list below).
Tier 3 design/implementation has not yet started** -- an Explore sub-agent has been
dispatched to map the exact Java-side plumbing (the `ReflectanceData` interface and its
implementations, where `extractReflectance.frag`'s render targets get read back to CPU
memory, and the exact call site that builds `MatrixBuilderSample` streams and invokes
`MatrixBuilder.build()`) before the 2D accumulator and the `extractReflectance.frag`
restructuring (to emit 3 domain positions instead of 1, and raw undivided radiance) can be
implemented correctly. `SpecularWeightModel.java`'s `getSamples()`/`getBasisFunctions()`
also still need updating to raw-radiance/summed-3-light form once Tier 3's data flow is
understood.

## Files changed so far

- `shaders/colorappearance/colorappearance.glsl` -- `LIGHTS_PER_VIEW` define + explicit
  light-index/slot API (see above).
- `shaders/colorappearance/colorappearance_multi_as_single.glsl` -- single-view
  `getLightInfoForSlot(slot, position)` convenience wrapper.
- `shaders/specularfit/average.frag` -- Tier 1, uses `getCombinedLightIntensity`.
- `shaders/specularfit/estimateDiffuse.frag` -- Tier 2, radiance-space diffuse-albedo solve.
- `shaders/specularfit/estimateDiffuseTranslucent.frag` -- Tier 2, same pattern feeding the
  constant+linear-in-nDotL regression.
- `shaders/specularfit/estimateNormals.frag` -- Tier 2, LM Jacobian summed across lights in
  radiance space (both the `USE_LEVENBERG_MARQUARDT` and legacy non-LM paths).
- `shaders/specularfit/errorCalc.frag`, `finalErrorCalc.frag`, `ggxErrorCalc.frag` -- Tier 2,
  radiance-space error comparison.
- `shaders/specularfit/normalError.glsl` -- Tier 2, `calculateError()` radiance-space,
  used by `estimateNormals.frag`'s LM step-acceptance test.

## Status: implementation complete, verified end-to-end

All of Tier 1, Tier 2, and Tier 3 are implemented:

- `shaders/specularfit/extractReflectance.frag` -- restructured to emit raw (undivided)
  observed radiance once per pixel, plus one halfway/geomRatio/weight/validity sample AND
  one incidentRadiance sample per light slot, packed into a single array output at one
  fixed `layout(location=1)` (GLSL 330's `layout(location=...)` only accepts a bare integer
  literal, not a `1 + LIGHTS_PER_VIEW`-style constant expression -- that needs GLSL 4.40 /
  ARB_enhanced_layouts -- caught by an actual `ShaderCompileFailureException` on the first
  end-to-end run and fixed by packing both arrays into one).
- `src/kintsugi3d/optimization/function/CrossLightAccumulator.java` -- the new 2D
  cross-light accumulator (see its Javadoc for the full derivation: builds small dense
  histograms over the bounded basis-resolution domain rather than a per-sample
  O(functionCount^2) brute force, reduced via a couple of small matrix products).
- `src/kintsugi3d/builder/fit/decomposition/ReflectanceMatrixBuilder.java` -- calls the
  existing single-light `MatrixBuilder` once per light slot (diagonal terms + RHS) and
  `CrossLightAccumulator` once per unordered light-slot pair (cross terms), all
  accumulating into the same shared `MatrixSystem`.
- `src/kintsugi3d/builder/fit/decomposition/SpecularWeightModel.java` -- divides by the
  COMBINED incident radiance from all valid lights (not raw radiance directly -- a naive
  radiance-space reformulation would silently change the implicit sample weighting even for
  the single-light case), predicting the matching irradiance-weighted-average reflectance.
- `src/kintsugi3d/builder/fit/ReflectanceData.java`, `SpecularFitOptimizable.java`,
  `SpecularFitProcess.java` -- per-slot data plumbing and the `1 + 2*lightsPerView`
  framebuffer attachment count.
- New `lightsPerView` project setting (default 1, `DefaultSettings.java`), wired into
  `ViewSet.getShaderProgramBuilder`'s `LIGHTS_PER_VIEW` shader define.
- `calibrateLightIntensities()`/`initializeLightIntensities()` (Java,
  `GraphicsResourcesImageSpace`/`GraphicsResourcesBase`) -- checked, and these already loop
  over `getViewSet().getLightCount()` (not hardcoded to light 0), broadcasting the same
  calibrated intensity to every light -- correct as-is for identical/similar rig-mounted
  flashes, no change needed.
- `python/kintsugi3d/pipeline.py`'s `build_view_set()` -- generalized to accept a list of
  `light_offsets`/`light_intensities` (camera-rig-relative offsets, confirmed against
  `ViewSet.addLight()`'s raw/un-transformed storage and `colorappearance.glsl`'s
  `getLightVector()` -- a light's world position for view v is
  `cameraWorldPos(v) + cameraRotation(v) * offset`, i.e. it rigidly follows the camera).
- `threedo-triple-flash` worktree's `threedo/kintsugi.py` and `threedo/alicevision_sfm.py`
  -- `run_kintsugi3d()`/`run_pipeline()` take `light_offsets_meters` and convert to scene
  units via the new `estimate_scale_from_arago_poses()` (derives scene-units-per-meter from
  the ratio of RMS camera-to-centroid distance between the SfM reconstruction and the
  Arago rig's own metric "Image poses.json" export -- no per-photo correspondence needed,
  since the renamed/re-encoded `sfm_images` no longer hash- or EXIF-match the original
  `arago/ImagesFlashTriple/RIG*.JPG` files needed for that; both pose sets sample the same
  physical capture sphere instead). Three independent scale estimators (mean/median/RMS
  distance ratio) agreed to ~1% for this dataset (~0.51-0.52 m per SfM unit; ~0.51m
  camera-to-centroid distance, physically plausible for this tabletop rig).

**Verified**: a full Maven build (JDK 11 -- this project's `pom.xml` targets Java 11,
incompatible with the newer JDKs otherwise available in this environment; a portable
Temurin JDK 11 + Maven 3.9.16 were downloaded into a job-scratch directory for the build,
not installed system-wide) produces `target/Kintsugi3DBuilder-1.5.3-shaded.jar` with no
compile errors. A full end-to-end headless run against
`/data/shared/mug_020926_sfm_ImagesFlashTriple/sfm_images` with 3 lights (center + left +
right, ~1m each) at a 256x256 smoke-test resolution completed without errors: the specular
fit converges (basis clustering, normal/diffuse optimization, error calculation all ran
through their normal iteration counts), and the exported textures (albedo, diffuse, normal,
specular, roughness, ORM, per-material weight maps) are non-degenerate (sensible
mean/std/coverage, not flat or all-zero).

**Not yet done**: a full-resolution (2048x2048 or similar) production run, and any
qualitative/quantitative comparison against the single-light (center-only) fit to see
whether the 3-light BRDF actually improves material-model accuracy for this object -- the
whole point of the exercise. See runtime.csv in each run's output directory for timing.
