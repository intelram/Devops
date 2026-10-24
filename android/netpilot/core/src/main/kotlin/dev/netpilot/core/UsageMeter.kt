package dev.netpilot.core

/**
 * "Mobile data used today" from Android's cumulative counters (TrafficStats), which reset
 * at every reboot and know nothing about days. Sampled whenever the app or the protection
 * service runs. Usage between two samples on the same boot is counted in full; usage
 * between the last sample before midnight and the first one after it is not counted, so
 * the figure is a slight under-estimate ("about").
 */
data class UsageState(val day: String, val bootId: Long, val lastCounter: Long, val today: Long)

object UsageMeter {
    fun update(prev: UsageState?, day: String, bootId: Long, counter: Long): UsageState {
        if (counter < 0) return prev ?: UsageState(day, bootId, 0, 0) // counters unsupported
        if (prev == null || prev.day != day) return UsageState(day, bootId, counter, 0)
        val delta = when {
            prev.bootId != bootId -> counter        // rebooted: counters restarted from zero
            counter < prev.lastCounter -> counter   // counter reset without a reboot
            else -> counter - prev.lastCounter
        }
        return UsageState(day, bootId, counter, prev.today + delta)
    }
}
