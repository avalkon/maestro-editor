package com.digero.maestro.shortcuts;

/** Pure bar-aligned transport calculations for stopped, paused and playing sequences. */
public final class BarNavigation {
    private BarNavigation() { }

    /**
     * @param positionTick current transport tick
     * @param originTick preview's initial offset (zero for source MIDI)
     * @param ticksPerBar active time signature's full measure in transport ticks
     * @param bars signed whole bars relative to the start of the current bar
     * @param endTick final playable tick
     */
    public static long barStart(long positionTick, long originTick, long ticksPerBar, int bars, long endTick) {
        if (ticksPerBar <= 0) throw new IllegalArgumentException("ticksPerBar");
        long relative = Math.max(0L, positionTick - originTick);
        long currentBar = relative / ticksPerBar;
        long destination = Math.max(0L, currentBar + bars);
        long maxBars = Math.max(0L, (endTick - originTick) / ticksPerBar);
        if (destination > maxBars) return Math.max(0L, endTick);
        return Math.min(Math.max(0L, endTick), Math.max(0L, originTick + destination * ticksPerBar));
    }
}
