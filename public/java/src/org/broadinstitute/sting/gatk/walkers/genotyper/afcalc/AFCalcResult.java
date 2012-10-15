/*
 * Copyright (c) 2010.
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
 * THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package org.broadinstitute.sting.gatk.walkers.genotyper.afcalc;

import com.google.java.contract.Ensures;
import com.google.java.contract.Requires;
import org.broadinstitute.sting.utils.MathUtils;
import org.broadinstitute.sting.utils.Utils;
import org.broadinstitute.sting.utils.variantcontext.Allele;

import java.util.*;

/**
 * Describes the results of the AFCalc
 *
 * Only the bare essentials are represented here, as all AFCalc models must return meaningful results for
 * all of these fields.
 *
 * Note that all of the values -- i.e. priors -- are checked now that they are meaningful, which means
 * that users of this code can rely on the values coming out of these functions.
 */
public class AFCalcResult {
    private final static int AF0 = 0;
    private final static int AF1p = 1;
    private final static int LOG_10_ARRAY_SIZES = 2;

    private final double[] log10LikelihoodsOfAC;
    private final double[] log10PriorsOfAC;
    private final double[] log10PosteriorsOfAC;

    private final Map<Allele, Double> log10pNonRefByAllele;

    /**
     * The AC values for all ALT alleles at the MLE
     */
    private final int[] alleleCountsOfMLE;

    int nEvaluations = 0;

    /**
     * The list of alleles actually used in computing the AF
     */
    private List<Allele> allelesUsedInGenotyping = null;

    /**
     * Create a results object capability of storing results for calls with up to maxAltAlleles
     */
    public AFCalcResult(final int[] alleleCountsOfMLE,
                        final int nEvaluations,
                        final List<Allele> allelesUsedInGenotyping,
                        final double[] log10LikelihoodsOfAC,
                        final double[] log10PriorsOfAC,
                        final Map<Allele, Double> log10pNonRefByAllele) {
        if ( allelesUsedInGenotyping == null || allelesUsedInGenotyping.size() < 1 ) throw new IllegalArgumentException("allelesUsedInGenotyping must be non-null list of at least 1 value " + allelesUsedInGenotyping);
        if ( alleleCountsOfMLE == null ) throw new IllegalArgumentException("alleleCountsOfMLE cannot be null");
        if ( alleleCountsOfMLE.length != allelesUsedInGenotyping.size() - 1) throw new IllegalArgumentException("alleleCountsOfMLE.length " + alleleCountsOfMLE.length + " != allelesUsedInGenotyping.size() " + allelesUsedInGenotyping.size());
        if ( nEvaluations < 0 ) throw new IllegalArgumentException("nEvaluations must be >= 0 but saw " + nEvaluations);
        if ( log10LikelihoodsOfAC.length != 2 ) throw new IllegalArgumentException("log10LikelihoodsOfAC must have length equal 2");
        if ( log10PriorsOfAC.length != 2 ) throw new IllegalArgumentException("log10PriorsOfAC must have length equal 2");
        if ( log10pNonRefByAllele == null ) throw new IllegalArgumentException("log10pNonRefByAllele cannot be null");
        if ( log10pNonRefByAllele.size() != allelesUsedInGenotyping.size() - 1 ) throw new IllegalArgumentException("log10pNonRefByAllele has the wrong number of elements: log10pNonRefByAllele " + log10pNonRefByAllele + " but allelesUsedInGenotyping " + allelesUsedInGenotyping);
        if ( ! allelesUsedInGenotyping.containsAll(log10pNonRefByAllele.keySet()) ) throw new IllegalArgumentException("log10pNonRefByAllele doesn't contain all of the alleles used in genotyping: log10pNonRefByAllele " + log10pNonRefByAllele + " but allelesUsedInGenotyping " + allelesUsedInGenotyping);
        if ( ! goodLog10ProbVector(log10LikelihoodsOfAC, LOG_10_ARRAY_SIZES, false) ) throw new IllegalArgumentException("log10LikelihoodsOfAC are bad " + Utils.join(",", log10LikelihoodsOfAC));
        if ( ! goodLog10ProbVector(log10PriorsOfAC, LOG_10_ARRAY_SIZES, true) ) throw new IllegalArgumentException("log10priors are bad " + Utils.join(",", log10PriorsOfAC));

        this.alleleCountsOfMLE = alleleCountsOfMLE;
        this.nEvaluations = nEvaluations;
        this.allelesUsedInGenotyping = allelesUsedInGenotyping;

        this.log10LikelihoodsOfAC = Arrays.copyOf(log10LikelihoodsOfAC, LOG_10_ARRAY_SIZES);
        this.log10PriorsOfAC = Arrays.copyOf(log10PriorsOfAC, LOG_10_ARRAY_SIZES);
        this.log10PosteriorsOfAC = computePosteriors(log10LikelihoodsOfAC, log10PriorsOfAC);
        this.log10pNonRefByAllele = new HashMap<Allele, Double>(log10pNonRefByAllele);
    }

    /**
     * Return a new AFCalcResult with a new prior probability
     *
     * @param log10PriorsOfAC
     * @return
     */
    public AFCalcResult withNewPriors(final double[] log10PriorsOfAC) {
        return new AFCalcResult(alleleCountsOfMLE, nEvaluations, allelesUsedInGenotyping, log10LikelihoodsOfAC, log10PriorsOfAC, log10pNonRefByAllele);
    }

    /**
     * Returns a vector with maxAltAlleles values containing AC values at the MLE
     *
     * The values of the ACs for this call are stored in the getAllelesUsedInGenotyping order,
     * starting from index 0 (i.e., the first alt allele is at 0).  The vector is always
     * maxAltAlleles in length, and so only the first getAllelesUsedInGenotyping.size() - 1 values
     * are meaningful.
     *
     * @return a vector with allele counts, not all of which may be meaningful
     */
    @Ensures("result != null")
    public int[] getAlleleCountsOfMLE() {
        return alleleCountsOfMLE;
    }

    /**
     * Returns the AC of allele a la #getAlleleCountsOfMLE
     *
     * @param allele the allele whose AC we want to know.  Error if its not in allelesUsedInGenotyping
     * @throws IllegalStateException if allele isn't in allelesUsedInGenotyping
     * @return the AC of allele
     */
    public int getAlleleCountAtMLE(final Allele allele) {
        return getAlleleCountsOfMLE()[altAlleleIndex(allele)];
    }

    /**
     * Returns the number of cycles used to evaluate the pNonRef for this AF calculation
     *
     * @return the number of evaluations required to produce the answer for this AF calculation
     */
    public int getnEvaluations() {
        return nEvaluations;
    }

    /**
     * Get the list of alleles actually used in genotyping.
     *
     * Due to computational / implementation constraints this may be smaller than
     * the actual list of alleles requested
     *
     * @return a non-empty list of alleles used during genotyping
     */
    @Ensures({"result != null", "! result.isEmpty()"})
    public List<Allele> getAllelesUsedInGenotyping() {
        return allelesUsedInGenotyping;
    }

    /**
     * Get the log10 normalized -- across all ACs -- posterior probability of AC == 0 for all alleles
     *
     * @return
     */
    @Ensures({"goodLog10Probability(result)"})
    public double getLog10PosteriorOfAFEq0() {
        return log10PosteriorsOfAC[AF0];
    }

    /**
     * Get the log10 normalized -- across all ACs -- posterior probability of AC > 0 for any alleles
     *
     * @return
     */
    @Ensures({"goodLog10Probability(result)"})
    public double getLog10PosteriorOfAFGT0() {
        return log10PosteriorsOfAC[AF1p];
    }

    /**
     * Get the log10 unnormalized -- across all ACs -- likelihood of AC == 0 for all alleles
     *
     * @return
     */
    @Ensures({"goodLog10Probability(result)"})
    public double getLog10LikelihoodOfAFEq0() {
        return log10LikelihoodsOfAC[AF0];
    }

    /**
     * Get the log10 unnormalized -- across all ACs -- likelihood of AC > 0 for any alleles
     *
     * @return
     */
    @Ensures({"goodLog10Probability(result)"})
    public double getLog10LikelihoodOfAFGT0() {
        return log10LikelihoodsOfAC[AF1p];
    }

    /**
     * Get the log10 unnormalized -- across all ACs -- prior probability of AC == 0 for all alleles
     *
     * @return
     */
    @Ensures({"goodLog10Probability(result)"})
    public double getLog10PriorOfAFEq0() {
        return log10PriorsOfAC[AF0];
    }

    /**
     * Get the log10 unnormalized -- across all ACs -- prior probability of AC > 0
     *
     * @return
     */
    @Ensures({"goodLog10Probability(result)"})
    public double getLog10PriorOfAFGT0() {
        return log10PriorsOfAC[AF1p];
    }

    @Override
    public String toString() {
        final List<String> byAllele = new LinkedList<String>();
        for ( final Allele a : getAllelesUsedInGenotyping() )
            if ( a.isNonReference() ) byAllele.add(String.format("%s => MLE %d / posterior %.2f", a, getAlleleCountAtMLE(a), getLog10PosteriorOfAFGt0ForAllele(a)));
        return String.format("AFCalc%n\t\tlog10PosteriorOfAFGT0=%.2f%n\t\t%s", getLog10LikelihoodOfAFGT0(), Utils.join("\n\t\t", byAllele));
    }

    /**
     * Are we sufficiently confidence in being non-ref that the site is considered polymorphic?
     *
     * We are non-ref if the probability of being non-ref > the emit confidence (often an argument).
     * Suppose posterior AF > 0 is log10: -5 => 10^-5
     * And that log10minPNonRef is -3.
     * We are considered polymorphic since 10^-5 < 10^-3 => -5 < -3
     *
     * @param log10minPNonRef the log10 scaled min pr of being non-ref to be considered polymorphic
     *
     * @return true if there's enough confidence (relative to log10minPNonRef) to reject AF == 0
     */
    public boolean isPolymorphic(final Allele allele, final double log10minPNonRef) {
        return getLog10PosteriorOfAFGt0ForAllele(allele) >= log10minPNonRef;
    }

    /**
     * Returns the log10 probability that allele is segregating
     *
     * Unlike the sites-level annotation, this calculation is specific to allele, and can be
     * used to separately determine how much evidence there is that allele is independently
     * segregating as opposed to the site being polymorphic with any allele.  In the bi-allelic
     * case these are obviously the same but for multiple alt alleles there can be lots of
     * evidence for one allele but not so much for any other allele
     *
     * @param allele the allele we're interested in, must be in getAllelesUsedInGenotyping
     * @return the log10 probability that allele is segregating at this site
     */
    @Ensures("goodLog10Probability(result)")
    public double getLog10PosteriorOfAFGt0ForAllele(final Allele allele) {
        final Double log10pNonRef = log10pNonRefByAllele.get(allele);
        if ( log10pNonRef == null ) throw new IllegalArgumentException("Unknown allele " + allele);
        return log10pNonRef;
    }

    /**
     * Returns the log10 normalized posteriors given the log10 likelihoods and priors
     *
     * @param log10LikelihoodsOfAC
     * @param log10PriorsOfAC
     *
     * @return freshly allocated log10 normalized posteriors vector
     */
    @Requires("log10LikelihoodsOfAC.length == log10PriorsOfAC.length")
    @Ensures("goodLog10ProbVector(result, LOG_10_ARRAY_SIZES, true)")
    private static double[] computePosteriors(final double[] log10LikelihoodsOfAC, final double[] log10PriorsOfAC) {
        final double[] log10UnnormalizedPosteriors = new double[log10LikelihoodsOfAC.length];
        for ( int i = 0; i < log10LikelihoodsOfAC.length; i++ )
            log10UnnormalizedPosteriors[i] = log10LikelihoodsOfAC[i] + log10PriorsOfAC[i];

        // necessary because the posteriors may be so skewed that the log-space normalized value isn't
        // good, so we have to try both log-space normalization as well as the real-space normalization if the
        // result isn't good
        final double[] logNormalized = MathUtils.normalizeFromLog10(log10UnnormalizedPosteriors, true, true);
        if ( goodLog10ProbVector(logNormalized, logNormalized.length, true) )
            return logNormalized;
        else
            return MathUtils.normalizeFromLog10(log10UnnormalizedPosteriors, true, false);
    }

    /**
     * Check that the log10 prob vector vector is well formed
     *
     * @param vector
     * @param expectedSize
     * @param shouldSumToOne
     *
     * @return true if vector is well-formed, false otherwise
     */
    private static boolean goodLog10ProbVector(final double[] vector, final int expectedSize, final boolean shouldSumToOne) {
        if ( vector.length != expectedSize ) return false;

        for ( final double pr : vector ) {
            if ( ! goodLog10Probability(pr) )
                return false;
        }

        if ( shouldSumToOne && MathUtils.compareDoubles(MathUtils.sumLog10(vector), 1.0, 1e-4) != 0 )
            return false;

        return true; // everything is good
    }

    /**
     * Computes the offset into linear vectors indexed by alt allele for allele
     *
     * Things like our MLE allele count vector are indexed by alt allele index, with
     * the first alt allele being 0, the second 1, etc.  This function computes the index
     * associated with allele.
     *
     * @param allele the allele whose alt index we'd like to know
     * @throws IllegalArgumentException if allele isn't in allelesUsedInGenotyping
     * @return an index value greater than 0 suitable for indexing into the MLE and other alt allele indexed arrays
     */
    @Requires("allele != null")
    @Ensures({"result >= 0", "result < allelesUsedInGenotyping.size() - 1"})
    private int altAlleleIndex(final Allele allele) {
        if ( allele.isReference() ) throw new IllegalArgumentException("Cannot get the alt allele index for reference allele " + allele);
        final int index = allelesUsedInGenotyping.indexOf(allele);
        if ( index == -1 )
            throw new IllegalArgumentException("could not find allele " + allele + " in " + allelesUsedInGenotyping);
        else
            return index - 1;
    }

    /**
     * Checks that the result is a well-formed log10 probability
     *
     * @param result a supposedly well-formed log10 probability value
     * @return true if result is really well formed
     */
    private static boolean goodLog10Probability(final double result) {
        return result <= 0.0 && ! Double.isInfinite(result) && ! Double.isNaN(result);
    }
}