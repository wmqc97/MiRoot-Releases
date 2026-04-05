package com.wmqc.miroot.lyrics;

/**
 * Euler / quaternion conversion and ops.
 */
public class QuaternionCalculator {
    private static final String TAG = "QuaternionCalculator";

    public static float[] fromEuler(float[] euler) {
        if (euler == null || euler.length < 3) {
            LogHelper.w(TAG, "euler array too short");
            return new float[]{0, 0, 0, 1};
        }

        float cx = (float) Math.cos(euler[0] / 2);
        float sx = (float) Math.sin(euler[0] / 2);
        float cy = (float) Math.cos(euler[1] / 2);
        float sy = (float) Math.sin(euler[1] / 2);
        float cz = (float) Math.cos(euler[2] / 2);
        float sz = (float) Math.sin(euler[2] / 2);

        float w = cx * cy * cz + sx * sy * sz;
        float x = sx * cy * cz - cx * sy * sz;
        float y = cx * sy * cz + sx * cy * sz;
        float z = cx * cy * sz - sx * sy * cz;

        return new float[]{x, y, z, w};
    }

    public static float[] toEuler(float[] quat) {
        if (quat == null || quat.length < 4) {
            LogHelper.w(TAG, "quat array too short");
            return new float[]{0, 0, 0};
        }

        float x = quat[0];
        float y = quat[1];
        float z = quat[2];
        float w = quat[3];

        float tx = 2 * (w * x + y * z);
        float ty = 1 - 2 * (x * x + y * y);
        float rx = (float) Math.atan2(tx, ty);

        float sy = 2 * (w * y - z * x);
        float clampedSy = Math.max(-1, Math.min(1, sy));
        float ry = (float) Math.asin(clampedSy);

        float tz = 2 * (w * z + x * y);
        float tw = 1 - 2 * (y * y + z * z);
        float rz = (float) Math.atan2(tz, tw);

        return new float[]{rx, ry, rz};
    }

    public static float[] multiply(float[] a, float[] b) {
        if (a == null || a.length < 4 || b == null || b.length < 4) {
            LogHelper.w(TAG, "quat multiply: array too short");
            return new float[]{0, 0, 0, 1};
        }

        float ax = a[0], ay = a[1], az = a[2], aw = a[3];
        float bx = b[0], by = b[1], bz = b[2], bw = b[3];

        float rx = aw * bx + ax * bw + ay * bz - az * by;
        float ry = aw * by - ax * bz + ay * bw + az * bx;
        float rz = aw * bz + ax * by - ay * bx + az * bw;
        float rw = aw * bw - ax * bx - ay * by - az * bz;

        return new float[]{rx, ry, rz, rw};
    }

    public static float[] inverse(float[] quat) {
        if (quat == null || quat.length < 4) {
            LogHelper.w(TAG, "inverse: array too short");
            return new float[]{0, 0, 0, 1};
        }

        float norm = quat[0] * quat[0] + quat[1] * quat[1] + quat[2] * quat[2] + quat[3] * quat[3];

        if (norm == 0) {
            LogHelper.w(TAG, "inverse: zero norm");
            return new float[]{0, 0, 0, 1};
        }

        return new float[]{-quat[0] / norm, -quat[1] / norm, -quat[2] / norm, quat[3] / norm};
    }

    public static float angle(float[] a, float[] b) {
        if (a == null || a.length < 4 || b == null || b.length < 4) {
            LogHelper.w(TAG, "angle: array too short");
            return 0;
        }

        float dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3];
        dot = Math.max(-1, Math.min(1, dot));

        return 2 * (float) Math.acos(Math.abs(dot));
    }

    public static float[] slerp(float[] a, float[] b, float t) {
        if (a == null || a.length < 4 || b == null || b.length < 4) {
            LogHelper.w(TAG, "slerp: array too short");
            return new float[]{0, 0, 0, 1};
        }

        t = Math.max(0, Math.min(1, t));

        float dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3];
        float[] bAdjusted = b.clone();

        if (dot < 0) {
            bAdjusted[0] = -b[0];
            bAdjusted[1] = -b[1];
            bAdjusted[2] = -b[2];
            bAdjusted[3] = -b[3];
            dot = -dot;
        }

        if (dot > 0.9995f) {
            float[] res = new float[]{
                a[0] + t * (bAdjusted[0] - a[0]),
                a[1] + t * (bAdjusted[1] - a[1]),
                a[2] + t * (bAdjusted[2] - a[2]),
                a[3] + t * (bAdjusted[3] - a[3]),
            };

            float len = (float) Math.sqrt(
                res[0] * res[0] + res[1] * res[1] + res[2] * res[2] + res[3] * res[3]
            );

            if (len > 0.0001f) {
                return new float[]{
                    res[0] / len, res[1] / len, res[2] / len, res[3] / len,
                };
            }

            return a.clone();
        }

        float theta0 = (float) Math.acos(dot);
        float theta = theta0 * t;
        float sinTheta = (float) Math.sin(theta);
        float sinTheta0 = (float) Math.sin(theta0);

        float s0 = (float) Math.cos(theta) - (dot * sinTheta) / sinTheta0;
        float s1 = sinTheta / sinTheta0;

        return new float[]{
            a[0] * s0 + bAdjusted[0] * s1,
            a[1] * s0 + bAdjusted[1] * s1,
            a[2] * s0 + bAdjusted[2] * s1,
            a[3] * s0 + bAdjusted[3] * s1,
        };
    }

    public static float[] rotateByGyro(float[] gyro, float[] basis, float delta) {
        if (gyro == null || gyro.length < 3 || basis == null || basis.length < 3) {
            LogHelper.w(TAG, "rotateByGyro: array too short");
            return new float[]{0, 0, 0};
        }

        return new float[]{
            basis[0] - gyro[0] * delta,
            basis[1] - gyro[1] * delta,
            basis[2] - gyro[2] * delta,
        };
    }
}
