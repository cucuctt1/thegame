package dev.tilesolver.safety

import dev.tilesolver.core.SafetyContext
import dev.tilesolver.core.SafetyGate
import dev.tilesolver.model.SafetyDecision

class DefaultSafetyGate(
    private val maxFrameAgeMillis: Long = 3_000,
    private val minTapIntervalMillis: Long = 300,
) : SafetyGate {
    override fun evaluate(context: SafetyContext): SafetyDecision {
        val c = context.calibration
        val point = context.move.coordinate
        if (context.foregroundPackage != context.configuredPackage) return SafetyDecision.Stop("configured game is not foreground")
        if (!c.isValid() || !context.state.observation.geometryValid) return SafetyDecision.Stop("invalid board geometry")
        if (!context.state.observation.recognizedScreen) return SafetyDecision.Stop("unrecognized game screen")
        if (context.nowMillis - context.lastFrameMillis > maxFrameAgeMillis) return SafetyDecision.Remap("capture frame is stale; retrying latest frame")
        if (context.nowMillis - context.lastTapMillis < minTapIntervalMillis) return SafetyDecision.Remap("tap rate limited")
        if (!c.board.contains(point.x.toInt(), point.y.toInt())) return SafetyDecision.Stop("tap outside board")
        if (c.exclusions.any { it.contains(point.x.toInt(), point.y.toInt()) }) return SafetyDecision.Stop("tap intersects exclusion zone")
        if (context.state.noOpCount >= 3) return SafetyDecision.Stop("three taps produced no board change")
        if (context.state.noOpCount == 2) return SafetyDecision.Remap("two taps produced no board change")
        return SafetyDecision.Allow
    }
}
