/*
 * MIT License
 *
 * Copyright 2017 Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.broadinstitute.dropseqrna.utils.editdistance;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;

import org.broadinstitute.dropseqrna.utils.ObjectCounter;

import htsjdk.samtools.util.Log;

/**
 * A utility class that takes a list of strings (ordered by prevalence to determine which barcodes are merged into which)
 * and generates a map, containing the non merged barcodes as keys and the values of each key are a list of barcodes that are merged into a key.
 * @author nemesh
 *
 */
public class MapBarcodesByEditDistance {

	private final int NUM_THREADS;
	private final Log log = Log.getInstance(MapBarcodesByEditDistance.class);
	private final int REPORT_PROGRESS_INTERVAL;
	private final boolean verbose;
	private ForkJoinPool forkJoinPool;


	public MapBarcodesByEditDistance (final boolean verbose, final int numThreads, final int reportProgressInterval) {
		this.verbose=verbose;
		this.NUM_THREADS=numThreads;
		this.REPORT_PROGRESS_INTERVAL=reportProgressInterval;
		// https://blog.krecan.net/2014/03/18/how-to-specify-thread-pool-for-java-8-parallel-streams/
		forkJoinPool = new ForkJoinPool(numThreads);
	}

	public MapBarcodesByEditDistance (final boolean verbose, final int reportProgressInterval) {
		this(verbose, 1, reportProgressInterval);
	}

	public MapBarcodesByEditDistance (final boolean verbose) {
		this(verbose, 1, 100000);
	}

	public BottomUpCollapseResult bottomUpCollapse (final ObjectCounter<String> barcodes, final int editDistance) {
		BottomUpCollapseResult result = new BottomUpCollapseResult();

		// ordered from smallest to largest.
		List<String> barcodeList = barcodes.getKeysOrderedByCount(false);

		// process [i] vs [i+1:(end-1)]
		// can't collapse the last barcode with nothing...
		int len=barcodeList.size();
		for (int i=0; i<(len-1); i++) {
			String smallBC = barcodeList.get(i);
			List<String> largerBarcodes= barcodeList.subList(i+1, len);
			Set<String> largerRelatedBarcodes= processSingleBarcode(smallBC, largerBarcodes, false, editDistance);
			// if there's just 1 larger neighbor, the result is unambiguous.
			if (largerRelatedBarcodes.size()==1 ) {
				String largerBarcode=largerRelatedBarcodes.iterator().next();
				// only add if the barcode is larger.
				// this avoids ordering issues with equally sized barcodes vs alphanumeric barcode sorting.
				if (barcodes.getCountForKey(smallBC)< barcodes.getCountForKey(largerBarcode))
					result.addPair(smallBC, largerBarcode);
			}
			if (largerRelatedBarcodes.size()>1)
				result.addAmbiguousBarcode(smallBC);
		}
		return result;
	}

	/**
	 * Perform edit distance collapse where all barcodes are eligible to be collapse.
	 * Barcodes are ordered by total number of counts.
	 * @param barcodes
	 * @param findIndels
	 * @param editDistance
	 * @return
	 */
	public Map<String, List<String>> collapseBarcodes (final ObjectCounter<String> barcodes, final boolean findIndels, final int editDistance) {
		List<String> coreBarcodes = barcodes.getKeysOrderedByCount(true);
		return (collapseBarcodes(coreBarcodes, barcodes, findIndels, editDistance));
	}

	public AdaptiveMappingResult collapseBarcodesAdaptive (final ObjectCounter<String> barcodes, final boolean findIndels, final int defaultEditDistance, final int minEditDistance, final int maxEditDistance) {
		List<String> coreBarcodes = barcodes.getKeysOrderedByCount(true);
		return (collapseBarcodesAdaptive(coreBarcodes, barcodes, findIndels, defaultEditDistance, minEditDistance, maxEditDistance));
	}

	/**
	 * Collapses a list of barcodes.
	 * This works by iterating through every core barcode (ordered from largest to smallest in the barcodes object), and mapping any other barcode within editDistance
	 * to this barcode.  This means that the number of coreBarcodes at the end is less than or equal to the number of core barcodes submitted.  These barcodes are the keys of the output object.
	 * The barcodes that are not in the coreBarcodes list are eligible to be collapsed into a core barcode, but will never absorb other barcodes.
	 * We use coreBarcodes in order to limit the scope of the computational work, as the number of coreBarcodes can be small (the number of cells)
	 * compared to the total number of barcodes (number of beads + bead sequencing errors.)
	 * Smaller barcodes are always collapsed into larger ones.  Each barcode only exists once in the output - if a barcode A is edit distance 1 away
	 * from barcode B and C, it will be assigned to whichever of B and C is larger.
	 * @param coreBarcodes A list of barcode strings that are considered "core" or primary barcodes.
	 * @param barcodes An exhaustive list of all barcodes (both core and non-core) with assigned counts of observations of these barcodes.
	 * @param findIndels If true, we use Levenshtein indel sensitive collapse.  If false, use Hamming distance.
	 * @param editDistance The maximum edit distance two barcodes can be at to be collapsed.
	 * @return
	 */
	public Map<String, List<String>> collapseBarcodes(List<String> coreBarcodes, ObjectCounter<String> barcodes, final boolean findIndels, final int editDistance) {
		// don't allow side effects to modify input lists.
		coreBarcodes = new ArrayList<>(coreBarcodes);
		barcodes = new ObjectCounter<>(barcodes);

		Map<String, List<String>> result = new HashMap<>();
		int count = 0;
		int numBCCollapsed=0;

		List<String> barcodeList = barcodes.getKeysOrderedByCount(true);
		int coreBarcodeCount=coreBarcodes.size();
		long startTime = System.currentTimeMillis();
		while (coreBarcodes.isEmpty()==false) {
			String b = coreBarcodes.get(0);
			count++;
			coreBarcodes.remove(b);
			barcodeList.remove(b);

			Set<String> closeBC=processSingleBarcode(b, barcodeList, findIndels, editDistance);
			numBCCollapsed+=closeBC.size();

			if (result.containsKey(b))
				log.error("Result should never have core barcode");

			List<String> closeBCList = new ArrayList<>(closeBC);
			Collections.sort(closeBCList);
			result.put(b, closeBCList);

			barcodeList.removeAll(closeBC);
			coreBarcodes.removeAll(closeBC);
			if (this.REPORT_PROGRESS_INTERVAL!=0 && count % this.REPORT_PROGRESS_INTERVAL == 0) {
				if (barcodes.getSize()>10000) log.info("Processed [" + count + "] records, totals BC Space left [" + barcodeList.size() +"]", " # collapsed this set [" + numBCCollapsed+"]");
				numBCCollapsed=0;
			}
		}
		if (verbose) {
			long endTime = System.currentTimeMillis();
			long duration = (endTime - startTime)/1000;
			log.info("Collapse with [" + this.NUM_THREADS +"] threads took [" + duration + "] seconds to process");
			log.info("Started with core barcodes [" +coreBarcodeCount+  "] ended with [" + count + "] num collapsed [" +  (coreBarcodeCount-count) +"]");
		}
		return (result);
	}

	/**
	 * Collapses a list of barcodes.
	 * This works by iterating through every core barcode (ordered from largest to smallest in the barcodes object), and mapping any other barcode close
	 * to this barcode.
	 *
	 * In the case of adaptive collapse, "close" uses the edit distance distribution of all barcodes to the core barcode being examined, and assumes that this
	 * distribution is bimodal.  The edit distance of lowest density is selected as the maximal edit distance.
	 *
	 * This means that the number of coreBarcodes at the end is less than or equal to the number of core barcodes submitted.  These barcodes are the keys of the output object.
	 * The barcodes that are not in the coreBarcodes list are eligible to be collapsed into a core barcode, but will never absorb other barcodes.
	 * We use coreBarcodes in order to limit the scope of the computational work, as the number of coreBarcodes can be small (the number of cells)
	 * compared to the total number of barcodes (number of beads + bead sequencing errors.)
	 * Smaller barcodes are always collapsed into larger ones.  Each barcode only exists once in the output - if a barcode A is edit distance 1 away
	 * from barcode B and C, it will be assigned to whichever of B and C is larger.
	 * @param coreBarcodes A list of barcode strings that are considered "core" or primary barcodes.
	 * @param barcodes An exhaustive list of all barcodes (both core and non-core) with assigned counts of observations of these barcodes.
	 * @param findIndels If true, we use Levenshtein indel sensitive collapse.  If false, use Hamming distance.
	 * @param defaultEditDistance If the discovered edit distance threshold is less than this number, this is used instead.  Set to 0 to effectively ignore the parameter.
	 * @return
	 */
	public AdaptiveMappingResult collapseBarcodesAdaptive(List<String> coreBarcodes, ObjectCounter<String> barcodes, final boolean findIndels, final int defaultEditDistance, final int minEditDistance, final int maxEditDistance) {
		// don't allow side effects to modify input lists.
		coreBarcodes = new ArrayList<>(coreBarcodes);
		barcodes = new ObjectCounter<>(barcodes);

		Set<String> allBarcodes = new HashSet<>();
		allBarcodes.addAll(coreBarcodes);
		allBarcodes.addAll(barcodes.getKeys());

		Map<String, List<String>> result = new HashMap<>();
		int count = 0;
		int numBCCollapsed=0;

		List<String> barcodeList = barcodes.getKeysOrderedByCount(true);

		int coreBarcodeCount=coreBarcodes.size();
		long startTime = System.currentTimeMillis();
		List<EditDistanceMappingMetric> metrics = new ArrayList<>();

		while (coreBarcodes.isEmpty()==false) {
			String b = coreBarcodes.get(0);
			count++;
			coreBarcodes.remove(b);
			barcodeList.remove(b);

			// find the edit distance threshold.
			int editDistanceDiscovered=findEditDistanceThreshold(b, allBarcodes, findIndels, minEditDistance, maxEditDistance);

			// constrain to min/max edit distances.  Retain the original discovered edit distance.
			int editDistance=editDistanceDiscovered;
			//if (editDistance > maxEditDistance) editDistance=defaultEditDistance;
			if (editDistance > maxEditDistance | editDistanceDiscovered==-1) editDistance=defaultEditDistance;

			Set<String> closeBC=processSingleBarcode(b, barcodeList, findIndels, editDistance);

			numBCCollapsed+=closeBC.size();
			// Steve reports all barcodes, not just collapsed ones.
			// if (closeBC.size()>0) {
				int mergedObservations=getMergedNumObservations(b, closeBC, barcodes);
				EditDistanceMappingMetric metric = new EditDistanceMappingMetric(b, closeBC.size(), editDistance, editDistanceDiscovered, barcodes.getCountForKey(b), mergedObservations);
				metrics.add(metric);
			// }

			if (result.containsKey(b))
				log.error("Result should never have core barcode");

			List<String> closeBCList = new ArrayList<>(closeBC);
			Collections.sort(closeBCList);
			result.put(b, closeBCList);

			barcodeList.removeAll(closeBC);
			coreBarcodes.removeAll(closeBC);
			if (this.REPORT_PROGRESS_INTERVAL!=0 && count % this.REPORT_PROGRESS_INTERVAL == 0) {
				if (barcodes.getSize()>10000) log.info("Processed [" + count + "] records, totals BC Space left [" + barcodeList.size() +"]", " # collapsed this set [" + numBCCollapsed+"]");
				numBCCollapsed=0;
			}
		}
		if (verbose) {
			long endTime = System.currentTimeMillis();
			long duration = (endTime - startTime)/1000;
			log.info("Collapse with [" + this.NUM_THREADS +"] threads took [" + duration + "] seconds to process");
			log.info("Started with core barcodes [" +coreBarcodeCount+  "] ended with [" + count + "] num collapsed [" +  (coreBarcodeCount-count) +"]");
		}
		AdaptiveMappingResult r = new AdaptiveMappingResult(result, metrics);
		return (r);
	}

	private int getMergedNumObservations (final String originalBC, final Set<String> closeBC, final ObjectCounter<String> barcodes) {
		int total=barcodes.getCountForKey(originalBC);
		for (String c: closeBC)
			total+=barcodes.getCountForKey(c);
		return total;
	}

	/**
	 * Given a target barcode and a list of other barcodes, calculate the edit distance distribution of all barcodes.
	 * It should be bimodal if there are some neighbors and many unrelated neighbors.
	 * Find the minimum edit distance that separates the two peaks.
	 * @param targetBarcode
	 * @param allBarcodes
	 * @param findIndels
	 * @param minEditDistance the minimum edit distance to scan
	 * @param maxEditDistance 2 times this number is the maximum to scan for a minimum density.
	 * @return the edit distance threshold discovered, or -1 if none was found.
	 */
	/*
	public int findEditDistanceThresholdExperimental (final String targetBarcode, final Set<String> allBarcodes, final boolean findIndels, final int minEditDistance, final int maxEditDistance) {
		int [] edList = getEditDistanceDistributioneMultithreaded(targetBarcode, allBarcodes, findIndels);
		// build and populate an object counter.
		ObjectCounter<Integer> counts = new ObjectCounter<>();
		Arrays.stream(edList).forEach(x -> counts.increment(x));
		// System.out.println(Arrays.toString(edList));

		// loop through to find the minimum count point, from the lowest edit distance to the maximum ED.
		// int maxED = Arrays.stream(edList).max().getAsInt();
		List<Integer> result = new ArrayList<>();
		int resultCount=Integer.MAX_VALUE;
		// find the minimum position in the object counter.  This may include keys that don't exist (ie: they are 0.)
		// if there are multiple minimums, then select the lowest.
		// don't check edit distance 0.
		for (int i=minEditDistance; i<maxEditDistance; i++) {
			int count = counts.getCountForKey(i);
			if (count==resultCount) {
				resultCount=count;
				result.add(i);
			}
			if (count<resultCount) {
				resultCount=count;
				result.clear();
				result.add(i);
			}
		}

		// there may be ties.  select the first entry (smallest ED)
		Collections.sort(result);
		return result.get(0);
	}
	*/

	public Integer findEditDistanceThreshold (final String targetBarcode, final Set<String> allBarcodes, final boolean findIndels, final int minEditDistance, final int maxEditDistance) {
		int [] edList = getEditDistanceDistributioneMultithreaded(targetBarcode, allBarcodes, findIndels);
		// build and populate an object counter.
		ObjectCounter<Integer> counts = new ObjectCounter<>();
		Arrays.stream(edList).forEach(x -> counts.increment(x));
		// System.out.println(Arrays.toString(edList));

		// loop through to find the minimum count point, from the lowest edit distance to the maximum ED.
		// int maxED = Arrays.stream(edList).max().getAsInt();
		List<Integer> result = new ArrayList<>();
		int resultCount=Integer.MAX_VALUE;
		// find the minimum position in the object counter.  This may include keys that don't exist (ie: they are 0.)
		// if there are multiple minimums, then select the lowest.
		for (int i=minEditDistance; i<=maxEditDistance; i++) {
			int count = counts.getCountForKey(i);
			if (count==0) {
				resultCount=count;
				result.add(i);
			}
		}
		// no results found.
		if (result.size()==0) return -1;
		// special case: no entries within all of scanned results.  return 0.
		int numPosScanned=(maxEditDistance-minEditDistance)+1;
		if (result.size()==numPosScanned && resultCount==0) return 0;

		// there may be ties.  select the first entry (smallest ED)
		Collections.sort(result);
		int threshold=result.get(0);
		threshold=threshold-1; // steve reports the last filled position, not the empty one.
		return threshold;
	}

	private Set<String> processSingleBarcode(final String barcode, final List<String> comparisonBarcodes, final boolean findIndels, final int editDistance) {
		Set<String> closeBarcodes =null;

		// Replaced with java 8 lambda method. woot?
		if (this.NUM_THREADS>1 ) {
			 closeBarcodes=processSingleBarcodeMultithreaded(barcode, comparisonBarcodes, findIndels, editDistance);
			 return closeBarcodes;
		}
		if (findIndels)
			closeBarcodes = EDUtils.getInstance().getStringsWithinEditDistanceWithIndel(barcode,comparisonBarcodes, editDistance);
		else
			closeBarcodes = EDUtils.getInstance().getStringsWithinEditDistance(barcode,comparisonBarcodes, editDistance);

		return (closeBarcodes);
	}

	/**
	 * The Java lambda way.
	 * @param barcode
	 * @param comparisonBarcodes
	 * @param findIndels
	 * @param editDistance
	 * @return
	 */
	public Set<String> processSingleBarcodeMultithreaded(final String barcode, final List<String> comparisonBarcodes, final boolean findIndels, final int editDistance) {
		Set<String> result = new HashSet<>();
		try {
			if (findIndels)
				result = forkJoinPool.submit(() -> comparisonBarcodes.parallelStream().filter(x -> LevenshteinDistance.getIndelSlidingWindowEditDistance(barcode, x) <= editDistance).collect(Collectors.toSet())).get();
			else
				result = forkJoinPool.submit(() -> comparisonBarcodes.stream().filter(x -> HammingDistance.getHammingDistance(barcode, x) <= editDistance).collect(Collectors.toSet())).get();
		} catch (InterruptedException e) {
			e.printStackTrace();
		} catch (ExecutionException e) {
			e.printStackTrace();
		}
		return result;
	}

	/**
	 * The Java lambda way.
	 * @param barcode
	 * @param comparisonBarcodes
	 * @param findIndels
	 * @param editDistance
	 * @return
	 */
	private int [] getEditDistanceDistributioneMultithreaded(final String barcode, final Collection<String> comparisonBarcodes, final boolean findIndels) {
		int [] result=null;
		try {
			if (findIndels)
				result = forkJoinPool.submit(() -> comparisonBarcodes.parallelStream().mapToInt(x -> LevenshteinDistance.getIndelSlidingWindowEditDistance(barcode, x)).toArray()).get();
			else
				result = forkJoinPool.submit(() -> comparisonBarcodes.parallelStream().mapToInt(x -> HammingDistance.getHammingDistance(barcode, x)).toArray()).get();
		} catch (InterruptedException e) {
			e.printStackTrace();
		} catch (ExecutionException e) {
			e.printStackTrace();
		}
		return result;
	}


	public class AdaptiveMappingResult {
		private final Map<String, List<String>> barcodeCollapseResult;
		private final List<EditDistanceMappingMetric> metricResult;

		public AdaptiveMappingResult (final Map<String, List<String>> barcodeCollapseResult, final List<EditDistanceMappingMetric> metricResult) {
			this.barcodeCollapseResult=barcodeCollapseResult;
			this.metricResult=metricResult;
		}

		public Map<String, List<String>> getBarcodeCollapseResult() {
			return barcodeCollapseResult;
		}

		public List<EditDistanceMappingMetric> getMetricResult() {
			return metricResult;
		}

	}
	/*
	private List<BarcodeWithCount> getBarcodesWithCounts (final ObjectCounter<String> barcodes) {
		List<BarcodeWithCount> result = new ArrayList<>();
		List<String> keys = barcodes.getKeysOrderedByCount(true);
		for (String k: keys) {
			BarcodeWithCount b = new BarcodeWithCount(k, barcodes.getCountForKey(k));
			result.add(b);
		}
		return (result);
	}
	*/
}
