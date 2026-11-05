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

package kintsugi3d.optimization.function;

import kintsugi3d.optimization.MatrixSystem;
import org.ejml.data.DMatrixRMaj;
import org.ejml.simple.SimpleMatrix;

import java.util.function.IntToDoubleFunction;

/**
 * Accumulates the LHS (A'A) contributions from a pair of simultaneous lights that
 * {@link MatrixBuilder}/{@link AbstractBasisFunctions} cannot produce on their own.
 *
 * <p>Background: under a single light per view, one pixel sample contributes to the basis-shape-extraction
 * fitting system at exactly one domain position (a "halfway index" m), and {@link MatrixBuilder} exploits
 * that to sweep all samples, sorted by m, using cumulative running sums so that the expensive part of
 * distributing contributions into the (functionCount x functionCount)-sized matrix block only has to run
 * once per distinct integer floor(m) rather than once per sample.
 *
 * <p>Under several SIMULTANEOUS lights illuminating the same pixel (e.g. a triple-flash rig), one physical
 * pixel observation corresponds to a domain position PER LIGHT at once (each light has its own half-vector),
 * all contributing additively to the same shared observed radiance. Expanding the resulting least-squares
 * normal equations over N lights, the terms where both factors come from the SAME light reduce exactly to
 * the existing single-light sweep (see {@link kintsugi3d.builder.fit.decomposition.ReflectanceMatrixBuilder}
 * calling {@link MatrixBuilder#build} once per light, accumulating into the same shared system) -- but the
 * terms where the two factors come from DIFFERENT lights ("cross-light" terms) involve two independently
 * varying domain positions per sample, which the sorted single-value sweep cannot represent.
 *
 * <p>This class computes those cross-light terms differently: instead of trying to generalize the sorted
 * sweep to two dimensions, it builds small dense histograms over the (small, bounded) domain -- resolution
 * is the specular basis resolution (e.g. ~90) -- and reduces them to the needed matrix blocks via a couple
 * of small dense matrix products. Since the domain is small and bounded regardless of how many pixel
 * samples exist, this is cheap: building the histograms is a single linear pass over the samples (the same
 * asymptotic cost per sample as {@link MatrixBuilderSums#accept}), and reducing them via
 * {@code basis' * H * basis} is a fixed, tiny cost independent of the number of samples.
 *
 * <p>Call {@link #accumulate} once per valid cross-light pixel sample (i.e. only for samples where BOTH
 * lights of this pair are geometrically valid), then call {@link #finish} once to add the accumulated
 * contribution into the shared {@link MatrixSystem}.
 */
public class CrossLightAccumulator
{
    private final int instanceCount;
    private final BasisFunctions basisLibrary;
    private final MatrixSystem fittingSystem;
    private final double metallicity;

    /**
     * Number of discrete domain positions a sample's continuous "actual" value can floor/ceiling to,
     * i.e. basisLibrary.getOptimizedDomainSize() + 1 (the "+1" holds the ceiling bin for a sample whose
     * floor is the last valid domain index).
     */
    private final int domainBins;

    /**
     * basisFunctionTable.get(f, k) = basisLibrary.evaluate(k, f), precomputed once since it is the same
     * for every light (there is only one shared specular basis library) and every (b1, b2) instance pair.
     */
    private final SimpleMatrix basisFunctionTable;

    /**
     * Per (b1, b2) instance-pair running totals, indexed as [b1 * instanceCount + b2].
     */
    private final double[] crossConstConst;
    private final double[][] constSpecViaA; // binned by light A's floor, contributes via light A's constant term paired with light B's specular column
    private final double[][] constSpecViaB; // binned by light B's floor, contributes via light B's constant term paired with light A's specular column
    private final SimpleMatrix[] specSpec; // domainBins x domainBins histogram, indexed [b1 * instanceCount + b2]

    public CrossLightAccumulator(int instanceCount, BasisFunctions basisLibrary, MatrixSystem fittingSystem)
    {
        this.instanceCount = instanceCount;
        this.basisLibrary = basisLibrary;
        this.fittingSystem = fittingSystem;
        this.metallicity = basisLibrary.getMetallicity();

        this.domainBins = basisLibrary.getOptimizedDomainSize() + 1;

        int functionCount = basisLibrary.getFunctionCount();
        basisFunctionTable = new SimpleMatrix(domainBins, functionCount, DMatrixRMaj.class);
        for (int f = 0; f < domainBins; f++)
        {
            for (int k = 0; k < functionCount; k++)
            {
                basisFunctionTable.set(f, k, basisLibrary.evaluate(k, f));
            }
        }

        int pairCount = instanceCount * instanceCount;
        crossConstConst = new double[pairCount];
        constSpecViaA = new double[pairCount][domainBins];
        constSpecViaB = new double[pairCount][domainBins];
        specSpec = new SimpleMatrix[pairCount];
        for (int p = 0; p < pairCount; p++)
        {
            specSpec[p] = new SimpleMatrix(domainBins, domainBins, DMatrixRMaj.class);
        }
    }

    private double getConstantTerm(double analytic)
    {
        return metallicity * analytic + (1 - metallicity);
    }

    /**
     * Accumulates one pixel sample's contribution to the cross-light terms between light A and light B.
     * Only call this for samples where BOTH lights are geometrically valid (e.g. neither self-shadowed).
     *
     * @param weightByInstance The same per-pixel, per-instance weighting function used elsewhere for this sample
     *                         (e.g. the previous iteration's basis-membership weight).
     * @param sampleWeight The shared per-pixel sample weight (see the design notes on why this must be one
     *                     value shared across all lights of a pixel, not a per-light weight).
     * @param actualA Light A's continuous domain position for this sample (halfway index * basis resolution).
     * @param geomRatioA Light A's geometric factor (the "analytic" factor) for this sample.
     * @param actualB Light B's continuous domain position for this sample.
     * @param geomRatioB Light B's geometric factor for this sample.
     */
    public void accumulate(IntToDoubleFunction weightByInstance, double sampleWeight,
        double actualA, double geomRatioA, double actualB, double geomRatioB)
    {
        int domainSize = basisLibrary.getOptimizedDomainSize();

        int floorA = Math.min(domainSize - 1, (int) Math.floor(actualA));
        double tA = Math.max(0.0, 1.0 + floorA - actualA); // weight on floorA; (1 - tA) on floorA + 1
        int ceilA = floorA + 1;

        int floorB = Math.min(domainSize - 1, (int) Math.floor(actualB));
        double tB = Math.max(0.0, 1.0 + floorB - actualB);
        int ceilB = floorB + 1;

        double constantTermA = getConstantTerm(geomRatioA);
        double constantTermB = getConstantTerm(geomRatioB);

        for (int b1 = 0; b1 < instanceCount; b1++)
        {
            double wI1 = weightByInstance.applyAsDouble(b1);

            for (int b2 = 0; b2 < instanceCount; b2++)
            {
                double wI2 = weightByInstance.applyAsDouble(b2);
                double baseWeight = sampleWeight * wI1 * wI2;
                int pairIndex = b1 * instanceCount + b2;

                crossConstConst[pairIndex] += baseWeight * constantTermA * constantTermB;

                double constSpecViaACoeff = baseWeight * constantTermB * geomRatioA;
                constSpecViaA[pairIndex][floorA] += constSpecViaACoeff * tA;
                constSpecViaA[pairIndex][ceilA] += constSpecViaACoeff * (1 - tA);

                double constSpecViaBCoeff = baseWeight * constantTermA * geomRatioB;
                constSpecViaB[pairIndex][floorB] += constSpecViaBCoeff * tB;
                constSpecViaB[pairIndex][ceilB] += constSpecViaBCoeff * (1 - tB);

                double specSpecCoeff = baseWeight * geomRatioA * geomRatioB;
                SimpleMatrix h = specSpec[pairIndex];
                h.set(floorA, floorB, h.get(floorA, floorB) + specSpecCoeff * tA * tB);
                h.set(floorA, ceilB, h.get(floorA, ceilB) + specSpecCoeff * tA * (1 - tB));
                h.set(ceilA, floorB, h.get(ceilA, floorB) + specSpecCoeff * (1 - tA) * tB);
                h.set(ceilA, ceilB, h.get(ceilA, ceilB) + specSpecCoeff * (1 - tA) * (1 - tB));
            }
        }
    }

    /**
     * Distributes all accumulated contributions into the shared fitting system. Call once, after all
     * samples for this light pair have been passed to {@link #accumulate}.
     */
    public void finish()
    {
        for (int b1 = 0; b1 < instanceCount; b1++)
        {
            for (int b2 = 0; b2 < instanceCount; b2++)
            {
                int pairIndex = b1 * instanceCount + b2;

                // Constant-constant block: both light orderings (A const / B const) land on the same
                // (b1, b2) matrix entry and contribute an identical value, hence the factor of 2.
                fittingSystem.addToLHS(b1, b2, 2 * crossConstConst[pairIndex]);

                // Constant-specular cross blocks: light A's specular column (row index into instance b1)
                // paired with light B's constant term (instance b2), and vice versa. Both symmetric
                // placements mirror how AbstractBasisFunctions.contributeToFittingSystem places its own
                // const/non-const cross terms.
                SimpleMatrix viaARow = new SimpleMatrix(1, domainBins, DMatrixRMaj.class);
                SimpleMatrix viaBRow = new SimpleMatrix(1, domainBins, DMatrixRMaj.class);
                for (int f = 0; f < domainBins; f++)
                {
                    viaARow.set(0, f, constSpecViaA[pairIndex][f]);
                    viaBRow.set(0, f, constSpecViaB[pairIndex][f]);
                }
                SimpleMatrix constSpecFromA = viaARow.mult(basisFunctionTable); // 1 x functionCount, indexed by k1 (instance b1's specular column)
                SimpleMatrix constSpecFromB = viaBRow.mult(basisFunctionTable); // 1 x functionCount, indexed by k2 (instance b2's specular column)

                int functionCount = basisLibrary.getFunctionCount();
                for (int k = 0; k < functionCount; k++)
                {
                    int specB1 = instanceCount * (k + 1) + b1;
                    int specB2 = instanceCount * (k + 1) + b2;

                    double valFromA = constSpecFromA.get(0, k); // couples spec(k, b1) with const(b2)
                    fittingSystem.addToLHS(specB1, b2, valFromA);
                    fittingSystem.addToLHS(b2, specB1, valFromA);

                    double valFromB = constSpecFromB.get(0, k); // couples const(b1) with spec(k, b2)
                    fittingSystem.addToLHS(b1, specB2, valFromB);
                    fittingSystem.addToLHS(specB2, b1, valFromB);
                }

                // Specular-specular cross block: both light orderings (A's domain feeding k1 with B's domain
                // feeding k2, and vice versa) reduce to a matrix and its transpose -- see class Javadoc.
                SimpleMatrix m1 = basisFunctionTable.transpose().mult(specSpec[pairIndex]).mult(basisFunctionTable);
                SimpleMatrix symmetrized = m1.plus(m1.transpose());
                for (int k1 = 0; k1 < functionCount; k1++)
                {
                    int specK1B1 = instanceCount * (k1 + 1) + b1;
                    for (int k2 = 0; k2 < functionCount; k2++)
                    {
                        int specK2B2 = instanceCount * (k2 + 1) + b2;
                        fittingSystem.addToLHS(specK1B1, specK2B2, symmetrized.get(k1, k2));
                    }
                }
            }
        }
    }
}
