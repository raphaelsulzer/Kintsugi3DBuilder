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
import kintsugi3d.builder.fit.settings.BasisOptimizationSettings;
import kintsugi3d.optimization.MatrixSystem;
import kintsugi3d.optimization.function.BasisFunctions;
import kintsugi3d.optimization.function.CrossLightAccumulator;
import kintsugi3d.optimization.function.MatrixBuilder;
import kintsugi3d.optimization.function.MatrixBuilderSample;
import org.ejml.data.DMatrixRMaj;
import org.ejml.simple.SimpleMatrix;

import java.util.stream.IntStream;

import static org.ejml.dense.row.CommonOps_DDRM.multTransA;

/**
 * A helper class to maintain state necessary to efficiently build the matrix that can solve for reflectance.
 *
 * <p>Under LIGHTS_PER_VIEW &gt; 1 (see {@link ReflectanceData}), one pixel sample carries a domain position
 * PER LIGHT SLOT, all contributing to the same shared observed radiance. The same-light ("diagonal") terms
 * and the RHS vector reduce exactly to the existing single-light sweep -- computed here by constructing one
 * {@link MatrixBuilder} per light slot, each accumulating into the same shared {@link #contribution}. The
 * cross-light terms (between every pair of light slots) cannot be produced by that sweep and are instead
 * computed by {@link CrossLightAccumulator} -- see its Javadoc for why. This all reduces exactly to the
 * original single-light behavior when there is only one light slot.
 */
final class ReflectanceMatrixBuilder
{
    // Set to true to validate the MatrixBuilder implementation (should generally be turned off for much better efficiency).
    private static final boolean VALIDATE = false;

    /**
     * Reflectance information for all the data.
     */
    private final ReflectanceData reflectanceData;

    /**
     * Weight solution from the previous iteration.
     */
    private final SpecularDecomposition solution;

    private final BasisFunctions stepBasis;
    private final BasisOptimizationSettings basisSettings;

    /**
     * Stores both the LHS and RHS of the system to be solved.
     * LHS = A'A
     * RHS = A'y, possibly for multiple channels (i.e. red, green, blue for color).
     */
    private final MatrixSystem contribution;

    /**
     * Construct by accepting matrices where the final results will be stored.
     */
    ReflectanceMatrixBuilder(ReflectanceData reflectanceData, SpecularDecomposition solution,
        BasisFunctions stepBasis, MatrixSystem contribution, BasisOptimizationSettings settings)
    {
        this.solution = solution;

        //noinspection AssignmentOrReturnOfFieldWithMutableType
        this.reflectanceData = reflectanceData;
        this.basisSettings = settings;
        this.stepBasis = stepBasis;

        this.contribution = contribution;
    }

    public void execute()
    {
        int lightsPerView = reflectanceData.getLightsPerView();

        // Same-light diagonal terms + RHS: one MatrixBuilder per light slot (each needs its own internal
        // sweep state -- MatrixBuilder.build() is documented to only be called once per instance), all
        // accumulating into the same shared `contribution`.
        for (int slot = 0; slot < lightsPerView; slot++)
        {
            int slotFinal = slot;
            MatrixBuilder matrixBuilder =
                new MatrixBuilder(basisSettings.getBasisCount(), 3, basisSettings.getMetallicity(), stepBasis, contribution);

            matrixBuilder.build(
                IntStream.range(0, reflectanceData.size())
                    .filter(p -> reflectanceData.isSlotValid(p, slotFinal)) // Eliminate pixels without valid samples for this light
                    .mapToObj(p ->
                        new MatrixBuilderSample(
                            reflectanceData.getHalfwayIndex(p, slotFinal) * basisSettings.getBasisResolution(),
                            matrixBuilder.getBasisLibrary(), reflectanceData.getGeomRatio(p, slotFinal),
                            reflectanceData.getAdditionalWeight(p), b -> solution.getWeights(p).get(b),
                            reflectanceData.getRed(p), reflectanceData.getGreen(p), reflectanceData.getBlue(p))));
        }

        // Cross-light terms: one CrossLightAccumulator per unordered pair of light slots.
        for (int lightA = 0; lightA < lightsPerView; lightA++)
        {
            for (int lightB = lightA + 1; lightB < lightsPerView; lightB++)
            {
                int lightAFinal = lightA;
                int lightBFinal = lightB;

                CrossLightAccumulator crossLight =
                    new CrossLightAccumulator(basisSettings.getBasisCount(), stepBasis, contribution);

                IntStream.range(0, reflectanceData.size())
                    .filter(p -> reflectanceData.isSlotValid(p, lightAFinal) && reflectanceData.isSlotValid(p, lightBFinal))
                    .forEach(p -> crossLight.accumulate(
                        b -> solution.getWeights(p).get(b), reflectanceData.getAdditionalWeight(p),
                        reflectanceData.getHalfwayIndex(p, lightAFinal) * basisSettings.getBasisResolution(),
                        reflectanceData.getGeomRatio(p, lightAFinal),
                        reflectanceData.getHalfwayIndex(p, lightBFinal) * basisSettings.getBasisResolution(),
                        reflectanceData.getGeomRatio(p, lightBFinal)));

                crossLight.finish();
            }
        }

        if (VALIDATE)
        {
            validate();
        }
    }

    private static void assertBool(boolean condition, String message)
    {
        if (!condition)
        {
            throw new RuntimeException(message);
        }
    }

    private void validate()
    {
        // This brute-force validation only covers the single-light case (it predates LIGHTS_PER_VIEW > 1
        // support and does not attempt to replicate the cross-light term derivation in
        // CrossLightAccumulator). It intentionally throws for multi-light configurations rather than
        // silently validating only part of the system.
        assertBool(reflectanceData.getLightsPerView() == 1,
            "ReflectanceMatrixBuilder.validate() only supports LIGHTS_PER_VIEW == 1.");

        // Calculate the matrix products the slow way to make sure that the implementation is correct.
        SimpleMatrix mA = new SimpleMatrix(reflectanceData.size(),
                basisSettings.getBasisCount() * (basisSettings.getBasisComplexity() + 1), DMatrixRMaj.class);
        SimpleMatrix yRed = new SimpleMatrix(reflectanceData.size(), 1);
        SimpleMatrix yGreen = new SimpleMatrix(reflectanceData.size(), 1);
        SimpleMatrix yBlue = new SimpleMatrix(reflectanceData.size(), 1);

        for (int p = 0; p < reflectanceData.size(); p++)
        {
            if (reflectanceData.getVisibility(p) > 0)
            {
                float halfwayIndex = reflectanceData.getHalfwayIndex(p, 0);
                float geomRatio = reflectanceData.getGeomRatio(p, 0);

                // square-root since we're minimizing the sum of w * | y - A x |^2, not w^2 * | y - A x |^2
                float addlWeight = (float)Math.sqrt(reflectanceData.getAdditionalWeight(p));

                // Calculate which discretized MDF element the current sample belongs to.
                double mExact = halfwayIndex * basisSettings.getBasisResolution();
                int mFloor = Math.min(basisSettings.getBasisResolution() - 1, (int) Math.floor(mExact));

                yRed.set(p, addlWeight * reflectanceData.getRed(p));
                yGreen.set(p, addlWeight * reflectanceData.getGreen(p));
                yBlue.set(p, addlWeight * reflectanceData.getBlue(p));

                // When floor and exact are the same, t = 1.0.  When exact is almost a whole increment greater than floor, t approaches 0.0.
                // If mFloor is clamped to BASIS_RESOLUTION -1, then mExact will be much larger, so t = 0.0.
                double t = Math.max(0.0, 1.0 + mFloor - mExact);

                double diffuseFactor = stepBasis.getMetallicity() * geomRatio + (1 - stepBasis.getMetallicity());

                for (int b = 0; b < basisSettings.getBasisCount(); b++)
                {
                    // diffuse
                    mA.set(p, b, addlWeight * solution.getWeights(p).get(b) * diffuseFactor);

                    // specular
                    if (mExact < basisSettings.getBasisResolution())
                    {
                        // Iterate over the available step functions in the basis.
                        for (int s = 0; s < stepBasis.getFunctionCount(); s++)
                        {
                            // Evaluate each step function twice, to the left and right of the current sample.
                            double fFloor = stepBasis.evaluate(s, mFloor);
                            double fCeil = stepBasis.evaluate(s, mFloor + 1);

                            // Blend between the two sampled locations.
                            // In the case of a simple step function, fFloor & fCeil will both be either 1 or 0
                            // except at a boundary, where fFloor will be 1 and fCeil will be 0.
                            double fInterp = fFloor * t + fCeil * (1 - t);

                            // Index of the column where the coefficient will be stored in the big matrix.
                            int j = basisSettings.getBasisCount() * (s + 1) + b;

                            // specular with blending between the two sampled locations.
                            mA.set(p, j, addlWeight * geomRatio * solution.getWeights(p).get(b) * fInterp);
                        }
                    }
                }
            }
        }

        SimpleMatrix mATA = new SimpleMatrix(mA.numCols(), mA.numCols());
        SimpleMatrix vATyRed = new SimpleMatrix(mA.numCols(), 1);
        SimpleMatrix vATyGreen = new SimpleMatrix(mA.numCols(), 1);
        SimpleMatrix vATyBlue = new SimpleMatrix(mA.numCols(), 1);

        // Low level operations to avoid using unnecessary memory.
        multTransA(mA.getMatrix(), mA.getMatrix(), mATA.getMatrix());
        multTransA(mA.getMatrix(), yRed.getMatrix(), vATyRed.getMatrix());
        multTransA(mA.getMatrix(), yGreen.getMatrix(), vATyGreen.getMatrix());
        multTransA(mA.getMatrix(), yBlue.getMatrix(), vATyBlue.getMatrix());

        for (int i = 0; i < mATA.numRows(); i++)
        {
            assertBool(Math.abs(vATyRed.get(i, 0) - contribution.rhs[0].get(i, 0)) <= vATyRed.get(i, 0) * 0.001, "Red " + i);
            assertBool(Math.abs(vATyGreen.get(i, 0) - contribution.rhs[1].get(i, 0)) <= vATyGreen.get(i, 0) * 0.001, "Green  " + i);
            assertBool(Math.abs(vATyBlue.get(i, 0) - contribution.rhs[2].get(i, 0)) <= vATyBlue.get(i, 0) * 0.001, "Blue  " + i);

            for (int j = 0; j < mATA.numCols(); j++)
            {
                assertBool(Math.abs(mATA.get(i, j) - contribution.lhs.get(i, j)) <= mATA.get(i, j) * 0.001, "Matrix " + i + " " + j);
            }
        }
    }
}
