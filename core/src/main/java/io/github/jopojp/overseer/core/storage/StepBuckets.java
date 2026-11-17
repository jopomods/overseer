package io.github.jopojp.overseer.core.storage;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregates a step function into fixed-width buckets (ADR-0005).
 *
 * <p>Stored samples are thinned by a deadband, so a value holds until the next sample.
 * A plain average of the stored points would over-weight periods of change; buckets are
 * therefore time-weighted: each value counts for as long as it was in effect.
 *
 * <p>Steps may also be coarser aggregates (minute rows when building hours): each step then
 * carries its own min, max and average, which are merged accordingly.
 */
public final class StepBuckets {
    private StepBuckets() {}

    /** A value (or aggregate) in effect from {@code start} until the next step. */
    public record Step(long start, double min, double max, double avg, double last) {
        public static Step of(long start, double value) {
            return new Step(start, value, value, value, value);
        }
    }

    /** One bucket {@code [start, start + width)}; {@code coverage} = covered time in ms. */
    public record Bucket(long start, double min, double max, double avg, double last, long coverage) {}

    /**
     * @param steps steps ordered by start; a step before {@code from} provides the carried value
     * @param from  first bucket start (inclusive)
     * @param to    end of the last bucket (exclusive); buckets end at {@code to}
     * @param width bucket width in ms
     * @return buckets that have any value in effect, in order
     */
    public static List<Bucket> aggregate(List<Step> steps, long from, long to, long width) {
        var result = new ArrayList<Bucket>();
        if (steps.isEmpty() || to <= from || width <= 0) return result;
        int i = 0;
        // Advance to the last step starting at or before `from` (the carried value).
        while (i + 1 < steps.size() && steps.get(i + 1).start() <= from) i++;

        for (long bucketStart = from; bucketStart < to; bucketStart += width) {
            long bucketEnd = Math.min(bucketStart + width, to);
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            double weighted = 0;
            long covered = 0;
            double last = Double.NaN;

            int j = i;
            while (j < steps.size() && steps.get(j).start() < bucketEnd) {
                Step step = steps.get(j);
                long start = Math.max(step.start(), bucketStart);
                long end = j + 1 < steps.size() ? Math.min(steps.get(j + 1).start(), bucketEnd) : bucketEnd;
                if (step.start() <= bucketEnd && end > start) {
                    long span = end - start;
                    min = Math.min(min, step.min());
                    max = Math.max(max, step.max());
                    weighted += step.avg() * span;
                    covered += span;
                    last = step.last();
                }
                j++;
            }
            if (covered > 0) result.add(new Bucket(bucketStart, min, max, weighted / covered, last, covered));
            // Keep the last step that starts inside or before this bucket as carry for the next one.
            while (i + 1 < steps.size() && steps.get(i + 1).start() < bucketEnd) i++;
        }
        return result;
    }
}
