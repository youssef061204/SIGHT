package org.sight.meta

/**
 * Explicit geometry/layout contract, not automatic format detection. DAT 1.0.1 requests planar
 * YUV420, but its public VideoFrame has no strides, color standard, or orientation field.
 * This profile admits only packed I420 with BT.601 limited-range YUV conversion.
 * A physical host must supply its own chart-tested calibration record; mock defaults never apply.
 */
data class MetaFrameCalibration(
    val simulated: Boolean,
    val rotationDegrees: Int,
    val mirrored: Boolean,
    val validationRecord: String,
) {
    val colorStandard: String get() = "BT601_LIMITED"
    init {
        require(rotationDegrees in setOf(0, 90, 180, 270))
        require(validationRecord.isNotBlank())
    }

    fun requireCompatible(packet: MetaVideoPacket) {
        require(packet.source == if (simulated) "meta-dat-mock" else "meta-dat-glasses") {
            "A simulated calibration cannot authorize a physical camera (or the reverse)."
        }
        require(packet.width in 2..1920 && packet.height in 2..1920 && packet.width % 2 == 0 && packet.height % 2 == 0)
        require(packet.bytes.size.toLong() == packet.width.toLong() * packet.height * 3 / 2) {
            "Unsupported DAT frame padding/layout: exact packed I420 is required."
        }
        require(!packet.captureClockSynchronized) { "No DAT-to-handset exposure-clock calibration is implemented." }
    }

    companion object {
        /** Only the explicitly simulated, generated 0-degree HEVC file fixtures use this profile. */
        fun mockFixture() = MetaFrameCalibration(true, 0, false, "DAT-1.0.1-MDK-generated-color-chart")
    }
}
