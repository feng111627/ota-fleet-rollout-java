package com.github.feng111627.otafleetrolloutjava.model;

import java.util.Objects;

/** 语义化固件版本号 x.y.z，可比较。 */
public final class ModelVersion implements Comparable<ModelVersion> {
    private final int major;
    private final int minor;
    private final int patch;

    public ModelVersion(int major, int minor, int patch) {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("版本号不能为负数: " + major + "." + minor + "." + patch);
        }
        this.major = major;
        this.minor = minor;
        this.patch = patch;
    }

    public static ModelVersion parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("版本号不能为空");
        }
        String[] parts = text.trim().split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("版本号必须为 x.y.z 格式: " + text);
        }
        try {
            return new ModelVersion(
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("版本号必须为数字: " + text, e);
        }
    }

    public int getMajor() { return major; }
    public int getMinor() { return minor; }
    public int getPatch() { return patch; }

    @Override
    public int compareTo(ModelVersion other) {
        if (this.major != other.major) {
            return Integer.compare(this.major, other.major);
        }
        if (this.minor != other.minor) {
            return Integer.compare(this.minor, other.minor);
        }
        return Integer.compare(this.patch, other.patch);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ModelVersion that)) return false;
        return major == that.major && minor == that.minor && patch == that.patch;
    }

    @Override
    public int hashCode() {
        return Objects.hash(major, minor, patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
