/*
 * Copyright 2026 杭州开云集致科技有限公司
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.clougence.clouddm.ds.dameng.sql.parser;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Dameng grammar compatibility levels supported by the parser. */
public enum DmVersion {
    DM_6(6),
    DM_7(7),
    DM_8(8);

    /**
     * 达梦自述版本里的版本标记，例如 “DM Database Server 64 V8” 中的 V8。
     * 这类字符串的首个数字串是位数标识（64/32）而不是大版本，必须先按标记解析。
     */
    private static final Pattern  VERSION_MARKER = Pattern.compile("(?:^|[^0-9A-Za-z])[Vv](\\d{1,2})(?![0-9])");

    public static final DmVersion LATEST         = values()[values().length - 1];
    private final int             major;

    DmVersion(int major){
        this.major = major;
    }

    public static DmVersion parse(String version) {
        DmVersion resolved = resolve(parseMajor(version));
        return resolved == null ? LATEST : resolved;
    }

    /**
     * 版本字符串能否明确映射到受支持的大版本。不能映射时 {@link #parse(String)} 会退到 {@link #LATEST}，
     * 调用方需要知道这种退化时才能避免把不明确的版本当成高版本处理。
     */
    public static boolean isRecognized(String version) {
        return resolve(parseMajor(version)) != null;
    }

    private static DmVersion resolve(int major) {
        for (DmVersion candidate : values()) {
            if (candidate.major == major) {
                return candidate;
            }
        }
        return null;
    }

    public String versionString() {
        return Integer.toString(this.major);
    }

    public static boolean ge(DmVersion source, DmVersion target) {
        return source.major >= target.major;
    }

    private static int parseMajor(String version) {
        if (version == null || version.isBlank()) {
            return -1;
        }
        String value = version.trim();
        Matcher marker = VERSION_MARKER.matcher(value);
        if (marker.find()) {
            return parseIntOrUnknown(marker.group(1));
        }
        int start = 0;
        while (start < value.length() && !Character.isDigit(value.charAt(start))) {
            start++;
        }
        int end = start;
        while (end < value.length() && Character.isDigit(value.charAt(end))) {
            end++;
        }
        if (start == end) {
            return -1;
        }
        return parseIntOrUnknown(value.substring(start, end));
    }

    private static int parseIntOrUnknown(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
