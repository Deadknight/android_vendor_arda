package com.arda.vehicle.manager;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

final class GrantStore {
    static final int DEFAULT_ADAPTER_PRIORITY = 100;
    static final int MIN_ADAPTER_PRIORITY = 1;
    static final int MAX_ADAPTER_PRIORITY = 1000;
    private static final int FIRST_DYNAMIC_SOURCE_ID = 10_000;
    private static final String PREFS_NAME = "arda_vehicle_grants_v1";
    private static final String GRANT_PREFIX = "grant.";
    private static final String NEXT_SOURCE_ID = "next_source_id";

    private final SharedPreferences mPreferences;

    GrantStore(Context context) {
        Context storage = context.createDeviceProtectedStorageContext();
        mPreferences = storage.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    synchronized AdapterGrant grant(
            AdapterCandidate candidate,
            int priority,
            int[] publishCapabilities,
            int[] commandCapabilities) {
        int validatedPriority = Math.max(
                MIN_ADAPTER_PRIORITY,
                Math.min(MAX_ADAPTER_PRIORITY, priority));
        int[] publish = CapabilityParser.requireSubset(
                candidate.publishCapabilities,
                publishCapabilities,
                "publish");
        int[] commands = CapabilityParser.requireSubset(
                candidate.commandCapabilities,
                commandCapabilities,
                "command");
        if (publish.length == 0 && commands.length == 0) {
            throw new IllegalArgumentException("grant must contain at least one capability");
        }

        AdapterGrant previous = find(candidate.userId, candidate.component);
        int sourceId = previous != null ? previous.sourceId : allocateSourceIdLocked();
        AdapterGrant grant = new AdapterGrant(
                candidate.userId,
                candidate.component,
                sourceId,
                candidate.signingIdentity.grantAnchor(),
                validatedPriority,
                publish,
                commands,
                System.currentTimeMillis());
        boolean stored = mPreferences.edit()
                .putString(preferenceKey(candidate.userId, candidate.component), encode(grant))
                .commit();
        if (!stored) {
            throw new IllegalStateException("failed to persist adapter grant");
        }
        return grant;
    }

    synchronized boolean revoke(int userId, ComponentName component) {
        String key = preferenceKey(userId, component);
        boolean existed = mPreferences.contains(key);
        if (existed && !mPreferences.edit().remove(key).commit()) {
            throw new IllegalStateException("failed to persist adapter grant revocation");
        }
        return existed;
    }

    synchronized AdapterGrant find(int userId, ComponentName component) {
        String raw = mPreferences.getString(preferenceKey(userId, component), null);
        return raw != null ? decode(raw) : null;
    }

    synchronized List<AdapterGrant> listForUser(int userId) {
        List<AdapterGrant> grants = new ArrayList<>();
        for (Map.Entry<String, ?> entry : mPreferences.getAll().entrySet()) {
            if (!entry.getKey().startsWith(GRANT_PREFIX) || !(entry.getValue() instanceof String)) {
                continue;
            }
            AdapterGrant grant = decode((String) entry.getValue());
            if (grant != null && grant.userId == userId) {
                grants.add(grant);
            }
        }
        grants.sort(Comparator.comparing(grant -> grant.component.flattenToShortString()));
        return grants;
    }

    private int allocateSourceIdLocked() {
        int sourceId = mPreferences.getInt(NEXT_SOURCE_ID, FIRST_DYNAMIC_SOURCE_ID);
        if (sourceId <= 0 || sourceId == Integer.MAX_VALUE) {
            throw new IllegalStateException("ARDA source_id space exhausted");
        }
        if (!mPreferences.edit().putInt(NEXT_SOURCE_ID, sourceId + 1).commit()) {
            throw new IllegalStateException("failed to persist next ARDA source_id");
        }
        return sourceId;
    }

    private static String preferenceKey(int userId, ComponentName component) {
        String componentEncoded = Base64.encodeToString(
                component.flattenToString().getBytes(StandardCharsets.UTF_8),
                Base64.NO_WRAP | Base64.NO_PADDING | Base64.URL_SAFE);
        return GRANT_PREFIX + userId + "." + componentEncoded;
    }

    private static String encode(AdapterGrant grant) {
        try {
            JSONObject json = new JSONObject();
            json.put("user_id", grant.userId);
            json.put("component", grant.component.flattenToString());
            json.put("source_id", grant.sourceId);
            json.put("signer_anchor", grant.signerAnchor);
            json.put("priority", grant.priority);
            json.put("publish", CapabilityParser.toCsv(grant.publishCapabilities));
            json.put("commands", CapabilityParser.toCsv(grant.commandCapabilities));
            json.put("granted_at_ms", grant.grantedAtMillis);
            return json.toString();
        } catch (JSONException e) {
            throw new IllegalStateException("failed to encode adapter grant", e);
        }
    }

    private static AdapterGrant decode(String raw) {
        try {
            JSONObject json = new JSONObject(raw);
            ComponentName component = ComponentName.unflattenFromString(json.getString("component"));
            if (component == null) {
                return null;
            }
            return new AdapterGrant(
                    json.getInt("user_id"),
                    component,
                    json.getInt("source_id"),
                    json.getString("signer_anchor"),
                    json.getInt("priority"),
                    CapabilityParser.parse(json.optString("publish", ""), false),
                    CapabilityParser.parse(json.optString("commands", ""), true),
                    json.optLong("granted_at_ms", 0L));
        } catch (JSONException | IllegalArgumentException e) {
            return null;
        }
    }
}
