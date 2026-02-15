package com.raydesk.spatial

import kotlin.math.*

/**
 * Quaternion for gimbal-lock-free rotation tracking.
 *
 * Used to track relative head rotation from a reference position,
 * avoiding the gimbal lock issues that occur with Euler angles.
 * When the user recenters, we store the current orientation as the
 * reference quaternion, and all subsequent rotations are computed
 * relative to this reference, keeping the angles small.
 *
 * Convention: w + xi + yj + zk (scalar-first, matching Android sensor output)
 *
 * @property w Scalar component (cos(angle/2))
 * @property x X component of rotation axis * sin(angle/2)
 * @property y Y component of rotation axis * sin(angle/2)
 * @property z Z component of rotation axis * sin(angle/2)
 */
data class Quaternion(
    val w: Float,
    val x: Float,
    val y: Float,
    val z: Float
) {
    companion object {
        /**
         * Identity quaternion (no rotation).
         */
        fun identity() = Quaternion(1f, 0f, 0f, 0f)

        /**
         * Create quaternion from Android sensor values.
         *
         * Android's TYPE_GAME_ROTATION_VECTOR sensor outputs:
         * - values[0]: x * sin(angle/2)
         * - values[1]: y * sin(angle/2)
         * - values[2]: z * sin(angle/2)
         * - values[3]: cos(angle/2) (optional, may need to compute)
         *
         * @param values Sensor values array [x, y, z] or [x, y, z, w]
         * @return Normalized quaternion
         */
        fun fromSensorValues(values: FloatArray): Quaternion {
            val x = values[0]
            val y = values[1]
            val z = values[2]
            val w = if (values.size > 3) {
                values[3]
            } else {
                // Compute w from unit quaternion constraint: w^2 + x^2 + y^2 + z^2 = 1
                val normSq = x * x + y * y + z * z
                if (normSq < 1f) sqrt(1f - normSq) else 0f
            }
            return Quaternion(w, x, y, z).normalized()
        }

        /**
         * Create quaternion from yaw and pitch angles (degrees).
         *
         * This is primarily for testing purposes, allowing us to create
         * quaternions from human-readable angles.
         *
         * @param yawDegrees Rotation around Y axis (left/right head turn, positive = right)
         * @param pitchDegrees Rotation around X axis (up/down tilt, positive = up)
         * @return Combined rotation quaternion
         */
        fun fromYawPitch(yawDegrees: Float, pitchDegrees: Float): Quaternion {
            val yawRad = Math.toRadians(yawDegrees.toDouble()).toFloat() / 2f
            val pitchRad = Math.toRadians(pitchDegrees.toDouble()).toFloat() / 2f

            // Yaw rotation around Y axis (vertical)
            val qYaw = Quaternion(
                cos(yawRad),
                0f,
                sin(yawRad),
                0f
            )

            // Pitch rotation around X axis (horizontal)
            val qPitch = Quaternion(
                cos(pitchRad),
                sin(pitchRad),
                0f,
                0f
            )

            // Combined: pitch first, then yaw (order matters for quaternions)
            return qYaw.multiply(qPitch)
        }
    }

    /**
     * Quaternion multiplication (combines rotations).
     *
     * The result represents applying `this` rotation first, then `other`.
     * Note: Quaternion multiplication is NOT commutative (a*b != b*a).
     *
     * @param other The quaternion to multiply with
     * @return Combined rotation quaternion
     */
    fun multiply(other: Quaternion): Quaternion {
        return Quaternion(
            w = w * other.w - x * other.x - y * other.y - z * other.z,
            x = w * other.x + x * other.w + y * other.z - z * other.y,
            y = w * other.y - x * other.z + y * other.w + z * other.x,
            z = w * other.z + x * other.y - y * other.x + z * other.w
        )
    }

    /**
     * Quaternion inverse (conjugate for unit quaternions).
     *
     * For a unit quaternion, the inverse equals the conjugate.
     * The inverse of q, when multiplied by q, gives the identity.
     *
     * @return Inverse quaternion
     */
    fun inverse(): Quaternion {
        return Quaternion(w, -x, -y, -z)
    }

    /**
     * Normalize quaternion to unit length.
     *
     * Unit quaternions are required for representing rotations.
     * This handles edge cases where numerical errors accumulate.
     *
     * @return Normalized unit quaternion
     */
    fun normalized(): Quaternion {
        val mag = sqrt(w * w + x * x + y * y + z * z)
        return if (mag > 0.0001f) {
            Quaternion(w / mag, x / mag, y / mag, z / mag)
        } else {
            identity()
        }
    }

    /**
     * Calculate relative rotation from this reference to the current quaternion.
     *
     * This is the key method for head tracking: we store a reference orientation
     * (set when user recenters), and compute the relative rotation to the current
     * head orientation. This keeps the rotation small, avoiding gimbal lock.
     *
     * @param current The current orientation quaternion
     * @return Rotation that transforms this (reference) -> current
     */
    fun relativeTo(current: Quaternion): Quaternion {
        // relative = this.inverse() * current
        // This gives us the rotation FROM this TO current
        return this.inverse().multiply(current).normalized()
    }

    /**
     * Extract yaw and pitch from quaternion (degrees).
     *
     * Uses a method that avoids gimbal lock for head-tracking use case:
     * - Yaw: rotation around world Y axis (left/right head turn)
     * - Pitch: rotation around local X axis (up/down tilt)
     *
     * This conversion is stable for the small relative rotations we track
     * (typically <60° from reference), where gimbal lock is not an issue.
     *
     * @return Pair of (yaw, pitch) in degrees
     */
    fun toYawPitch(): Pair<Float, Float> {
        // Yaw (rotation around Y axis)
        // atan2(2(wy + xz), 1 - 2(y² + z²))
        val sinYaw = 2f * (w * y + x * z)
        val cosYaw = 1f - 2f * (y * y + z * z)
        val yaw = Math.toDegrees(atan2(sinYaw.toDouble(), cosYaw.toDouble())).toFloat()

        // Pitch (rotation around X axis) - clamped to avoid NaN at poles
        // asin(2(wx - zy))
        val sinPitch = (2f * (w * x - z * y)).coerceIn(-1f, 1f)
        val pitch = Math.toDegrees(asin(sinPitch.toDouble())).toFloat()

        return Pair(yaw, pitch)
    }
}
