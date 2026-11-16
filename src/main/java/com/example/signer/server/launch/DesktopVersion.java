package com.example.signer.server.launch;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A desktop app version {@code major.minor.patch} (an optional
 * {@code -SNAPSHOT}/{@code -rc1} suffix is accepted and ignored). Used to
 * refuse launch exchanges from apps older than the server supports.
 */
public record DesktopVersion(int major, int minor, int patch, String preRelease)
        implements Comparable<DesktopVersion> {

    private static final Pattern FORMAT =
            Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})"
                    + "(-[0-9A-Za-z]+(?:\\.[0-9A-Za-z]+)*)?$");
    /** Longest pre-release label accepted, so a version always fits 20 characters. */
    private static final int MAX_PRE_RELEASE = 8;

    /** The parsed version, or {@code null} when {@code text} is not one. */
    public static DesktopVersion parse(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = FORMAT.matcher(text.trim());
        if (!m.matches()) {
            return null;
        }
        String pre = m.group(4) == null ? null : m.group(4).substring(1);
        if (pre != null && pre.length() > MAX_PRE_RELEASE) {
            return null;
        }
        return new DesktopVersion(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)), pre);
    }

    @Override
    public int compareTo(DesktopVersion o) {
        if (major != o.major) {
            return Integer.compare(major, o.major);
        }
        if (minor != o.minor) {
            return Integer.compare(minor, o.minor);
        }
        if (patch != o.patch) {
            return Integer.compare(patch, o.patch);
        }
        // As in semantic versioning, a pre-release (1.2.0-beta) comes before
        // its release (1.2.0), so it never meets a 1.2.0 minimum.
        if (preRelease == null || o.preRelease == null) {
            return preRelease == null ? (o.preRelease == null ? 0 : 1) : -1;
        }
        return comparePreRelease(preRelease, o.preRelease);
    }

    /**
     * Semantic Versioning 2.0.0 §11: dot-separated identifiers compared left
     * to right; numeric ones numerically and below alphanumeric ones;
     * alphanumeric ones in ASCII order; a shorter list that is a prefix
     * comes first ({@code rc < rc.1 < rc.2 < rc.10}).
     */
    static int comparePreRelease(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            boolean xNum = x[i].chars().allMatch(Character::isDigit);
            boolean yNum = y[i].chars().allMatch(Character::isDigit);
            int c;
            if (xNum && yNum) {
                c = Long.compare(Long.parseLong(x[i]), Long.parseLong(y[i]));
            } else if (xNum != yNum) {
                c = xNum ? -1 : 1;
            } else {
                c = x[i].compareTo(y[i]);
            }
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(x.length, y.length);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch + (preRelease == null ? "" : "-" + preRelease);
    }
}
