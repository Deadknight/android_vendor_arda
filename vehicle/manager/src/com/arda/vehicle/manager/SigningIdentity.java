package com.arda.vehicle.manager;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.UserHandle;
import android.util.Base64;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

final class SigningIdentity {
    private static final String SINGLE_PREFIX = "single:";
    private static final String MULTI_PREFIX = "multi:";

    final boolean multipleSigners;
    final List<String> currentSignerDigests;
    final List<String> signingHistoryDigests;

    private SigningIdentity(
            boolean multipleSigners,
            List<String> currentSignerDigests,
            List<String> signingHistoryDigests) {
        this.multipleSigners = multipleSigners;
        this.currentSignerDigests = List.copyOf(currentSignerDigests);
        this.signingHistoryDigests = List.copyOf(signingHistoryDigests);
    }

    static SigningIdentity read(Context context, String packageName, int userId)
            throws PackageManager.NameNotFoundException {
        Context userContext = context.createContextAsUser(UserHandle.of(userId), 0);
        PackageInfo info = userContext.getPackageManager().getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES));
        SigningInfo signingInfo = info.signingInfo;
        if (signingInfo == null) {
            throw new PackageManager.NameNotFoundException(
                    "package has no SigningInfo: " + packageName);
        }

        if (signingInfo.hasMultipleSigners()) {
            List<String> current = digestAll(signingInfo.getApkContentsSigners());
            Collections.sort(current);
            return new SigningIdentity(true, current, Collections.emptyList());
        }

        List<String> history = digestAll(signingInfo.getSigningCertificateHistory());
        if (history.isEmpty()) {
            throw new PackageManager.NameNotFoundException(
                    "package has empty signing history: " + packageName);
        }
        List<String> current = List.of(history.get(history.size() - 1));
        return new SigningIdentity(false, current, history);
    }

    String grantAnchor() {
        if (multipleSigners) {
            return MULTI_PREFIX + String.join(",", currentSignerDigests);
        }
        return SINGLE_PREFIX + signingHistoryDigests.get(0);
    }

    boolean acceptsGrantAnchor(String anchor) {
        if (anchor == null) {
            return false;
        }
        if (multipleSigners) {
            return anchor.equals(MULTI_PREFIX + String.join(",", currentSignerDigests));
        }
        if (!anchor.startsWith(SINGLE_PREFIX)) {
            return false;
        }
        String digest = anchor.substring(SINGLE_PREFIX.length());
        return signingHistoryDigests.contains(digest);
    }

    String summary() {
        if (multipleSigners) {
            return "multiple:" + currentSignerDigests;
        }
        return "lineage:" + signingHistoryDigests;
    }

    private static List<String> digestAll(Signature[] signatures) {
        if (signatures == null || signatures.length == 0) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>(signatures.length);
        Arrays.stream(signatures).forEach(signature -> result.add(sha256(signature.toByteArray())));
        return result;
    }

    private static String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            return Base64.encodeToString(
                    digest,
                    Base64.NO_WRAP | Base64.NO_PADDING | Base64.URL_SAFE);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 unavailable", e);
        }
    }
}
