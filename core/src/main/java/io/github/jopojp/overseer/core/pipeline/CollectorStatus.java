package io.github.jopojp.overseer.core.pipeline;

/**
 * Immutable view of one provider's collection health ("collector cost" in the admin UI).
 *
 * @param provider             provider id
 * @param baseIntervalTicks    interval the provider asked for
 * @param currentIntervalTicks interval in effect; larger than base while throttled
 * @param budgetNanos          soft time budget per run
 * @param runs                 successful runs
 * @param errors               runs that threw
 * @param overruns             runs that exceeded the budget
 * @param lastNanos            duration of the last run
 * @param avgNanos             exponentially weighted average duration
 * @param maxNanos             longest run observed
 * @param lastError            message of the most recent error, or {@code null}
 */
public record CollectorStatus(
        String provider,
        int baseIntervalTicks,
        int currentIntervalTicks,
        long budgetNanos,
        long runs,
        long errors,
        long overruns,
        long lastNanos,
        long avgNanos,
        long maxNanos,
        String lastError) {

    public boolean throttled() {
        return currentIntervalTicks > baseIntervalTicks;
    }
}
