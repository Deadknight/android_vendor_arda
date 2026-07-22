package com.arda.vehicle.manager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

final class PropertyIdParser {
    private static final int MAX_PROPERTY_IDS = 512;

    private PropertyIdParser() {}

    static int[] parse(String raw, String label) {
        if (raw == null || raw.trim().isEmpty()) {
            return new int[0];
        }
        LinkedHashSet<Integer> values = new LinkedHashSet<>();
        for (String token : raw.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            final long decoded;
            try {
                decoded = Long.decode(trimmed);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("invalid " + label
                        + " property id: " + trimmed);
            }
            if (decoded <= 0 || decoded > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("invalid " + label
                        + " property id: " + trimmed);
            }
            values.add((int) decoded);
            if (values.size() > MAX_PROPERTY_IDS) {
                throw new IllegalArgumentException(label + " property request exceeds "
                        + MAX_PROPERTY_IDS + " entries");
            }
        }
        return values.stream().mapToInt(Integer::intValue).sorted().toArray();
    }

    static int[] requireSubset(int[] requested, int[] granted, String label) {
        int[] requestedSorted = sortedDistinctPositive(requested, label + " requested");
        int[] grantedSorted = sortedDistinctPositive(granted, label + " granted");
        for (int propertyId : grantedSorted) {
            if (Arrays.binarySearch(requestedSorted, propertyId) < 0) {
                throw new IllegalArgumentException(label + " property " + propertyId
                        + " was not requested by adapter");
            }
        }
        return grantedSorted;
    }

    static int[] sortedDistinctPositive(int[] values, String label) {
        if (values == null || values.length == 0) {
            return new int[0];
        }
        if (values.length > MAX_PROPERTY_IDS) {
            throw new IllegalArgumentException(label + " property list exceeds "
                    + MAX_PROPERTY_IDS + " entries");
        }
        int[] sorted = Arrays.stream(values).distinct().sorted().toArray();
        for (int propertyId : sorted) {
            if (propertyId <= 0) {
                throw new IllegalArgumentException("invalid " + label
                        + " property id=" + propertyId);
            }
        }
        return sorted;
    }

    static boolean contains(int[] sortedPropertyIds, int propertyId) {
        return propertyId > 0
                && sortedPropertyIds != null
                && Arrays.binarySearch(sortedPropertyIds, propertyId) >= 0;
    }

    static String toCsv(int[] values) {
        if (values == null || values.length == 0) {
            return "";
        }
        List<String> result = new ArrayList<>(values.length);
        for (int value : values) {
            result.add(Integer.toString(value));
        }
        return String.join(",", result);
    }
}
