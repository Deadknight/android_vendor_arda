package com.arda.vehicle.manager;

import com.arda.vehicle.sdk.ArdaVehicleContract;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

final class CapabilityParser {
    private CapabilityParser() {}

    static int[] parse(String raw, boolean commandCapabilities) {
        if (raw == null || raw.trim().isEmpty()) {
            return new int[0];
        }
        LinkedHashSet<Integer> values = new LinkedHashSet<>();
        for (String token : raw.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int value;
            try {
                value = Integer.parseInt(trimmed);
            } catch (NumberFormatException ignored) {
                value = ArdaVehicleContract.capabilityFromName(trimmed);
            }
            boolean valid = commandCapabilities
                    ? ArdaVehicleContract.isCommandCapability(value)
                    : ArdaVehicleContract.isPublishCapability(value);
            if (!valid) {
                throw new IllegalArgumentException(
                        "invalid " + (commandCapabilities ? "command" : "publish")
                                + " capability: " + trimmed);
            }
            values.add(value);
        }
        return values.stream().mapToInt(Integer::intValue).sorted().toArray();
    }

    static int[] requireSubset(int[] requested, int[] granted, String label) {
        int[] requestedSorted = sortedDistinct(requested);
        int[] grantedSorted = sortedDistinct(granted);
        for (int value : grantedSorted) {
            if (Arrays.binarySearch(requestedSorted, value) < 0) {
                throw new IllegalArgumentException(
                        label + " capability " + value + " was not requested by adapter");
            }
        }
        return grantedSorted;
    }

    static int[] sortedDistinct(int[] values) {
        if (values == null || values.length == 0) {
            return new int[0];
        }
        return Arrays.stream(values).distinct().sorted().toArray();
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
