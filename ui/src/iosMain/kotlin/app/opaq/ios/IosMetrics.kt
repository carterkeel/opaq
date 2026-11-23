package app.opaq.ios

import app.opaq.core.Engine
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSMeasurement
import platform.Foundation.NSUnit
import platform.Foundation.NSUnitDuration
import platform.Foundation.NSUnitInformationStorage
import platform.Foundation.timeIntervalSinceDate
import platform.MetricKit.MXCrashDiagnostic
import platform.MetricKit.MXCPUExceptionDiagnostic
import platform.MetricKit.MXDiagnosticPayload
import platform.MetricKit.MXDiskWriteExceptionDiagnostic
import platform.MetricKit.MXHangDiagnostic
import platform.MetricKit.MXMetricManager
import platform.MetricKit.MXMetricManagerSubscriberProtocol
import platform.MetricKit.MXMetricPayload
import platform.darwin.NSObject
import kotlin.math.roundToLong

/**
 * Apple's own measurements of Opaq, from MetricKit, written to the diagnostic log (#154, #165).
 * iOS hands them over about once a day: how long Opaq ran in the background, how much CPU,
 * location and network it used, and why iOS ended it in the background (memory, CPU, watchdog...).
 * Only totals and counts; nothing about where the phone was.
 */
@OptIn(ExperimentalForeignApi::class)
class IosMetrics(private val engine: Engine) : NSObject(), MXMetricManagerSubscriberProtocol {
    private var subscribed = false

    fun start() {
        if (subscribed) return
        subscribed = true
        MXMetricManager.sharedManager.addSubscriber(this)
    }

    override fun didReceiveMetricPayloads(payloads: List<*>) {
        payloads.filterIsInstance<MXMetricPayload>().forEach { report(it) }
    }

    override fun didReceiveDiagnosticPayloads(payloads: List<*>) {
        payloads.filterIsInstance<MXDiagnosticPayload>().forEach { p ->
            val parts = listOfNotNull(
                p.crashDiagnostics?.count { it is MXCrashDiagnostic }?.takeIf { it > 0 }?.let { "$it crash${if (it == 1) "" else "es"}" },
                p.hangDiagnostics?.count { it is MXHangDiagnostic }?.takeIf { it > 0 }?.let { "$it hang${if (it == 1) "" else "s"}" },
                p.cpuExceptionDiagnostics?.count { it is MXCPUExceptionDiagnostic }?.takeIf { it > 0 }?.let { "$it CPU limit${if (it == 1) "" else "s"}" },
                p.diskWriteExceptionDiagnostics?.count { it is MXDiskWriteExceptionDiagnostic }?.takeIf { it > 0 }?.let { "$it disk write limit${if (it == 1) "" else "s"}" },
            )
            if (parts.isNotEmpty()) engine.diagnostic("iOS report: ${parts.joinToString(", ")}")
        }
    }

    private fun report(p: MXMetricPayload) {
        val hours = p.timeStampEnd.timeIntervalSinceDate(p.timeStampBegin) / 3600
        val times = p.applicationTimeMetrics
        val cpu = p.cpuMetrics?.cumulativeCPUTime
        engine.diagnostic(
            "iOS report, last ${hours.roundToLong()} h: " +
                "on screen ${minutes(times?.cumulativeForegroundTime)}, " +
                "background ${minutes(times?.cumulativeBackgroundTime)} " +
                "(using location ${minutes(times?.cumulativeBackgroundLocationTime)}), " +
                "CPU ${seconds(cpu)}, peak memory ${megabytes(p.memoryMetrics?.peakMemoryUsage)}",
        )

        p.locationActivityMetrics?.let { l ->
            engine.diagnostic(
                "iOS report, location: best ${minutes(l.cumulativeBestAccuracyTime)}, " +
                    "navigation ${minutes(l.cumulativeBestAccuracyForNavigationTime)}, " +
                    "10 m ${minutes(l.cumulativeNearestTenMetersAccuracyTime)}, " +
                    "100 m ${minutes(l.cumulativeHundredMetersAccuracyTime)}, " +
                    "1 km ${minutes(l.cumulativeKilometerAccuracyTime)}, " +
                    "3 km ${minutes(l.cumulativeThreeKilometersAccuracyTime)}",
            )
        }

        p.networkTransferMetrics?.let { n ->
            engine.diagnostic(
                "iOS report, network: Wi-Fi ${megabytes(n.cumulativeWifiUpload)} up, ${megabytes(n.cumulativeWifiDownload)} down; " +
                    "cellular ${megabytes(n.cumulativeCellularUpload)} up, ${megabytes(n.cumulativeCellularDownload)} down",
            )
        }

        p.applicationExitMetrics?.backgroundExitData?.let { b ->
            val exits = listOf(
                "normal" to b.cumulativeNormalAppExitCount,
                "memory limit" to b.cumulativeMemoryResourceLimitExitCount,
                "memory pressure" to b.cumulativeMemoryPressureExitCount,
                "CPU limit" to b.cumulativeCPUResourceLimitExitCount,
                "watchdog" to b.cumulativeAppWatchdogExitCount,
                "background task timeout" to b.cumulativeBackgroundTaskAssertionTimeoutExitCount,
                "locked file" to b.cumulativeSuspendedWithLockedFileExitCount,
                "crash" to (b.cumulativeAbnormalExitCount + b.cumulativeBadAccessExitCount + b.cumulativeIllegalInstructionExitCount),
            ).filter { it.second > 0u }
            engine.diagnostic(
                "iOS report, ended in the background: " +
                    if (exits.isEmpty()) "never" else exits.joinToString(", ") { "${it.first} ${it.second}" },
            )
        }
    }

    // Whole numbers only: the diagnostic log blanks anything with three or more decimals.
    private fun minutes(m: NSMeasurement?): String = m?.let { "${(it.valueIn(NSUnitDuration.seconds) / 60).roundToLong()} min" } ?: "?"

    private fun seconds(m: NSMeasurement?): String = m?.let { "${it.valueIn(NSUnitDuration.seconds).roundToLong()} s" } ?: "?"

    private fun megabytes(m: NSMeasurement?): String = m?.let {
        val mb = it.valueIn(NSUnitInformationStorage.bytes) / 1_000_000
        if (mb < 10) "${(mb * 10).roundToLong() / 10.0} MB" else "${mb.roundToLong()} MB"
    } ?: "?"

    private fun NSMeasurement.valueIn(unit: NSUnit): Double = measurementByConvertingToUnit(unit).doubleValue
}
