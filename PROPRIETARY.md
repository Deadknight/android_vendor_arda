# Proprietary payload

ARDA's public source tree intentionally does not contain Google Automotive
Services binaries or payload files copied from a GAS image.

The build integration remains in this repository, but the following runtime
payload is materialized locally before building:

- `gas_car/files/`
- Google Maps split APKs
- Google Play services split APKs
- Phonesky / Play Store split APKs

Use:

```bash
vendor/arda/scripts/sync-proprietary.sh
```

The script downloads the pinned private payload release, verifies SHA-256
checksums, and places the files under `vendor/arda/`.

Do not commit the materialized payload to this repository.
