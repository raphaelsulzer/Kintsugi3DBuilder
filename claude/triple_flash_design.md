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

(Historical note: at one point during this work, Tier 3 looked like it might need only an
EM-style approximation reusing the existing 1D sweep unchanged. The user was asked and
explicitly chose the full mathematical rederivation described above instead. That
rederivation is now complete -- see "Status" below.)

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

**Verified, twice**: a 256x256 smoke test, then a real 1024x1024 run -- both completed
end-to-end against `/data/shared/mug_020926_sfm_ImagesFlashTriple/sfm_images` with 3 lights
(center + left + right, ~1m each, per the runs below), no errors, non-degenerate exported
textures. See "Current state of the repo" and "Verified run results" below for exact
commits/commands/output paths, and "Next steps" for what's genuinely still open (a
single-light-vs-3-light comparison -- the actual point of the exercise -- has NOT been done
yet).

## Current state of the repo (read this first if resuming from a fresh session)

Branch `triple-flash` (off `dev`), 4 commits ahead of `dev` as of this writing:

```
c7a5c26b Update triple-flash design doc: implementation complete, verified end-to-end
21998fed Fix GLSL330 layout(location) constant-expression error in extractReflectance.frag
a01d1b92 Implement full 3-light basis-shape extraction (Tier 3) and weight fitting
78db1645 Add multi-light (LIGHTS_PER_VIEW) support to specular-fit shaders
```

Working tree is clean (`git status`) as of this doc's last update. The companion worktree
`threedo-triple-flash` (branch `triple-flash` off `main`) has the Python-side wiring -- see
its own `claude/triple_flash_kintsugi_notes.md` for full details; short version: it calls
into this repo's Python bindings (`python/kintsugi3d/pipeline.py`) via `PYTHONPATH`, not via
the installed editable package (which points at the unrelated `/home/sulzer0000/code/
Kintsugi3DBuilder` checkout -- see this repo's own top-level `CLAUDE.md` "Critical gotcha"
section, which applies to `kintsugi3d` the same as it does to `threedo`).

### Reproducing the build toolchain

This machine has **no JDK 11** and **no Maven** installed anywhere persistent (checked:
`/usr/lib/jvm/` only has JRE-only Java 8 and Java 21 packages -- no `javac` in either; the
only `javac` found anywhere was bundled inside a VS Code extension, itself JDK 21). This
project's `pom.xml` targets Java 11 specifically (`<source>11</source><target>11</target>`)
and fails to compile under JDK 21 (`ImageHelper.java` uses the internal
`sun.java2d.cmm.ColorTransform` API, whose shape changed between JDK 11 and 21 in a way
that breaks compilation, not just a deprecation warning). Both a JDK 11 and Maven were
downloaded as portable/relocatable tarballs into this session's job-scratch directory
(`$CLAUDE_JOB_DIR/tmp/`, cleaned up when the job is deleted -- so this download step will
need to be repeated in a fresh session). Commands used (adjust the destination directory as
needed for a new session):

```bash
# JDK 11 (Eclipse Temurin, portable tarball)
mkdir -p /tmp/jdk11 && cd /tmp/jdk11
curl -sSL -o jdk11.tar.gz "https://api.adoptium.net/v3/binary/latest/11/ga/linux/x64/jdk/hotspot/normal/eclipse?project=jdk"
tar xzf jdk11.tar.gz   # extracts to e.g. jdk-11.0.32.1+1/

# Maven 3.9.16 (portable tarball; check https://downloads.apache.org/maven/maven-3/ for the current version if this one 404s)
mkdir -p /tmp/maven-install && cd /tmp/maven-install
curl -sSL -o maven.tar.gz https://downloads.apache.org/maven/maven-3/3.9.16/binaries/apache-maven-3.9.16-bin.tar.gz
tar xzf maven.tar.gz   # extracts to apache-maven-3.9.16/

export JAVA_HOME=/tmp/jdk11/jdk-11.0.32.1+1   # match the actual extracted dir name
export PATH=$JAVA_HOME/bin:/tmp/maven-install/apache-maven-3.9.16/bin:$PATH
```

`~/.m2/repository` already has ~39MB of cached dependencies from a prior build (not this
session's -- predates it), but NOT everything `mvn compile`/`package` needs -- the first
build must run online (not `-o`/offline) to fetch the rest; after that, `~/.m2` should be
complete enough to build offline. `mvn -v` should report `Java version: 11.x.x` if
`JAVA_HOME`/`PATH` are set correctly.

### Rebuilding the shaded jar

```bash
cd /home/sulzer0000/code/Kintsugi3DBuilder-triple-flash
mvn -q -DskipTests package
# -> target/Kintsugi3DBuilder-1.5.3-shaded.jar
```

Rebuild whenever any `.java` or `shaders/**` file in this repo changes (shaders are loaded
from disk relative to the repo root at runtime per `SpecularFitProcess`'s `new File(...)`
calls, i.e. NOT baked into the jar at a fixed path -- but the jar's `Main-Class` and its own
compiled Java code obviously do need a rebuild after Java changes; rebuilding after every
change, including shader-only ones, is simplest and was the practice used throughout this
session).

### Running

Two ways to use the resulting jar:

1. **Headless fit** (what was used to produce the verified runs below), via
   `threedo-triple-flash`'s CLI:
   ```bash
   source /opt/miniconda3/etc/profile.d/conda.sh && conda activate pcm
   export PYTHONPATH=/home/sulzer0000/code/threedo-triple-flash/src:/home/sulzer0000/code/Kintsugi3DBuilder-triple-flash/python
   export JAVA_HOME=<path to the JDK 11 from above>   # only needed if launching a JVM directly; JPype picks up a JVM on its own, but keeping this set is harmless and consistent
   python3 /home/sulzer0000/code/threedo-triple-flash/scripts/run_kintsugi3d.py \
     --sfm_json /data/shared/mug_020926_sfm_ImagesFlashTriple/alicevision_sfm/sfm.json \
     --alicevision_mvs_dir /data/shared/mug_020926_sfm_ImagesFlashTriple/alicevision_mvs \
     --images_dir /data/shared/mug_020926_sfm_ImagesFlashTriple/sfm_images \
     --masks_dir /data/shared/mug_020926_sfm_ImagesFlashTriple/masked_images \
     --output_dir /data/shared/mug_020926_sfm_ImagesFlashTriple/kintsugi3d_triple_flash \
     --texture_size 1024 \
     --jar_path /home/sulzer0000/code/Kintsugi3DBuilder-triple-flash/target/Kintsugi3DBuilder-1.5.3-shaded.jar \
     --light_offset_meters 0 0 0 --light_offset_meters 1 0 0 --light_offset_meters -1 0 0
   ```
   (`--light_offset_meters` was added to the CLI in this same session -- see
   `threedo-triple-flash`'s own notes for its exact semantics. Add `--clear` to overwrite an
   existing `--output_dir`.)

2. **Interactive GUI**, to actually open a saved `project.vset` and look at/relight the
   result (this jar's `Main-Class` is `kintsugi3d.builder.app.Kintsugi3DBuilder` -- it's the
   full GUI app, not headless-only):
   ```bash
   java -jar /home/sulzer0000/code/Kintsugi3DBuilder-triple-flash/target/Kintsugi3DBuilder-1.5.3-shaded.jar
   ```
   then File -> Open Project -> the `project.vset` under whichever `--output_dir` was used.
   **Must** be this jar (or a rebuild of this branch), not any stock/mainline Kintsugi3D
   Builder install -- stock code has no idea what `LIGHTS_PER_VIEW`, the `lightsPerView`
   project setting, or the multi-light math are, and would silently only use light index 0
   (the center flash) for anything light-index-driven, even though the saved project has 3
   registered lights. Needs a real display (X11/Wayland) -- was not attempted in this
   session (headless-only environment at the time).

### Verified run results

Both runs below used the same 3 lights (center + ~1m right + ~1m left, converted to ~1.94
scene units via `estimate_scale_from_arago_poses()` -- see the threedo-side notes) against
`/data/shared/mug_020926_sfm_ImagesFlashTriple/sfm_images`, masked with
`masked_images/*.png`, output into sibling directories of that dataset root:

- `kintsugi3d_triple_flash_smoketest/` -- 256x256, ~14 min total (`load view set` 371s,
  `specular fit` 424s, export ~2s). First successful run, used to catch the GLSL
  layout-qualifier bug (see commit `21998fed`).
- `kintsugi3d_triple_flash/` -- **1024x1024, the real result**, ~14 min total (`load view
  set` 373s, `specular fit` 483s, export ~3s -- specular fit barely more expensive than the
  256x256 smoke test, since most of its cost is per-view/per-sample image processing, not
  per-output-texel). Each output dir has its own `runtime.csv` with these exact numbers, a
  `project.vset`, `mesh/`, and `export/{gltf,textures}/`.

Exported texture sanity-checked directly (not just "did it crash"): `export/textures/*.png`
for the 1024x1024 run -- albedo/diffuse/normal/specular/roughness/ORM/8 material weight maps
all have plausible non-degenerate mean/std and correct alpha coverage (not flat, not all
transparent). Have NOT yet been opened in the GUI or otherwise visually inspected as
rendered images by a human.

## Next steps

1. **The actual point of the exercise, not yet done**: compare this 3-light fit's material
   accuracy against a single-light (center-flash-only) fit of the same object, to see
   whether the 3-light BRDF sum genuinely improves anything. Candidate approaches:
   `errorCalc`/`finalErrorCalc`'s own error texture (already exported, per-run, as
   `error.png`) could be compared directly between a 1-light and 3-light run of the same
   texture size; or render/relight comparisons in the GUI; or re-render each fit against
   held-out photos not used in that fit and compute PSNR (mirroring the methodology used
   elsewhere in this project's Gaussian-Splat work -- see the `threedo` repo's own
   `claude/mug_020926_flashcenter_sfm_notes.md` for that precedent).
2. Visually inspect the 1024x1024 result (GUI or the exported glTF/textures) -- nothing
   past raw statistics has been checked by eye yet.
3. If satisfied, consider a still-higher resolution (2048, matching this codebase's own
   usual default) production run.
4. `basisFunctions.csv` (also exported) has not been inspected at all -- could be a useful
   sanity check of whether the 3 material basis functions the fit converged to look
   physically sane (e.g. monotonically-decreasing microfacet-like shapes) or degenerate.
5. Not attempted: opening the result in the actual interactive GUI (needs a display).
