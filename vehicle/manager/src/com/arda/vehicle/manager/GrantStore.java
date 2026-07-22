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
    private static final String PREFS_NAME = "arda_vehicle_grants_v2";
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
            int[] commandCapabilities,
            int[] publishPropertyIds,
            int[] commandPropertyIds) {
        int validatedPriority = Math.max(
                MIN_ADAPTER_PRIORITY,
                Math.min(MAX_ADAPTER_PRIORITY, priority));
        int[] publishCaps = CapabilityParser.requireSubset(
                candidate.publishCapabilities,
                publishCapabilities,
                "publish");
        int[] commandCaps = CapabilityParser.requireSubset(
                candidate.commandCapabilities,
                commandCapabilities,
                "command");
        int[] publishProperties = PropertyIdParser.requireSubset(
                candidate.requestedPublishPropertyIds,
                publishPropertyIds,
                "publish");
        int[] commandProperties = PropertyIdParser.requireSubset(
                candidate.requestedCommandPropertyIds,
                commandPropertyIds,
                "command");

        if ((publishProperties.length == 0) != (publishCaps.length == 0)) {
            throw new IllegalArgumentException(
                    "publish capabilities and publish properties must be granted together");
        }
        if ((commandProperties.length == 0) != (commandCaps.length == 0)) {
            throw new IllegalArgumentException(
                    "command capabilities and command properties must be granted together");
        }
        if (publishProperties.length == 0 && commandProperties.length == 0) {
            throw new IllegalArgumentException(
                    "grant must contain at least one exact vehicle property");
        }

        AdapterGrant previous = find(candidate.userId, candidate.component);
        boolean sameSigningIdentity = previous != null
                && candidate.signingIdentity.acceptsGrantAnchor(previous.signerAnchor);
        int sourceId = sameSigningIdentity
                ? previous.sourceId
                : allocateSourceIdLocked();
        long now = System.currentTimeMillis();
        long grantedAt = sameSigningIdentity ? previous.grantedAtMillis : now;
        AdapterGrant grant = new AdapterGrant(
                candidate.userId,
                candidate.component,
                sourceId,
                candidate.signingIdentity.grantAnchor(),
                validatedPriority,
                publishCaps,
                commandCaps,
                publishProperties,
                commandProperties,
                true,
                grantedAt,
                now);
        persistLocked(grant);
        return grant;
    }

    synchronized AdapterGrant setEnabled(
            int userId, ComponentName component, boolean enabled) {
        AdapterGrant current = find(userId, component);
        if (current == null) {
            throw new IllegalArgumentException("adapter grant not found: " + component);
        }
        AdapterGrant updated = new AdapterGrant(
                current.userId,
                current.component,
                current.sourceId,
                current.signerAnchor,
                current.priority,
                current.publishCapabilities,
                current.commandCapabilities,
                current.publishPropertyIds,
                current.commandPropertyIds,
                enabled,
                current.grantedAtMillis,
                System.currentTimeMillis());
        persistLocked(updated);
        return updated;
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
            if (!entry.getKey().startsWith(GRANT_PREFIX)
                    || !(entry.getValue() instanceof String)) {
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

    private void persistLocked(AdapterGrant grant) {
        boolean stored = mPreferences.edit()
                .putString(preferenceKey(grant.userId, grant.component), encode(grant))
                .commit();
        if (!stored) {
            throw new IllegalStateException("failed to persist adapter grant");
        }
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
            json.put("schema_version", AdapterGrant.SCHEMA_VERSION);
            json.put("user_id", grant.userId);
            json.put("component", grant.component.flattenToString());
            json.put("source_id", grant.sourceId);
            json.put("signer_anchor", grant.signerAnchor);
            json.put("priority", grant.priority);
            json.put("enabled", grant.enabled);
            json.put("publish_capabilities",
                    CapabilityParser.toCsv(grant.publishCapabilities));
            json.put("command_capabilities",
                    CapabilityParser.toCsv(grant.commandCapabilities));
            json.put("publish_properties",
                    PropertyIdParser.toCsv(grant.publishPropertyIds));
            json.put("command_properties",
                    PropertyIdParser.toCsv(grant.commandPropertyIds));
            json.put("granted_at_ms", grant.grantedAtMillis);
            json.put("updated_at_ms", grant.updatedAtMillis);
            return json.toString();
        } catch (JSONException e) {
            throw new IllegalStateException("failed to encode adapter grant", e);
        }
    }

    private static AdapterGrant decode(String raw) {
        try {
            JSONObject json = new JSONObject(raw);
            if (json.optInt("schema_version", 0) != AdapterGrant.SCHEMA_VERSION) {
                return null;
            }
            ComponentName component = ComponentName.unflattenFromString(
                    json.getString("component"));
            if (component == null) {
                return null;
            }
            return new AdapterGrant(
                    json.getInt("user_id"),
                    component,
                    json.getInt("source_id"),
                    json.getString("signer_anchor"),
                    json.getInt("priority"),
                    CapabilityParser.parse(
                            json.optString("publish_capabilities", ""), false),
                    CapabilityParser.parse(
                            json.optString("command_capabilities", ""), true),
                    PropertyIdParser.parse(
                            json.optString("publish_properties", ""), "publish grant"),
                    PropertyIdParser.parse(
                            json.optString("command_properties", ""), "command grant"),
                    json.optBoolean("enabled", true),
                    json.optLong("granted_at_ms", 0L),
                    json.optLong("updated_at_ms", 0L));
        } catch (JSONException | IllegalArgumentException e) {
            return null;
        }
    }
}
