# ARDA Input Bridge

Platform-side input bridge for the ARDA AAP sender.

The app is platform-signed, privileged, persistent, and installed on `/system_ext` so it can use the hidden platform input API required by the bridge. It requests
`android.permission.INJECT_EVENTS`, listens only on `127.0.0.1:5276`, and
accepts the existing ARDA input line protocol:

- `DISPLAY <id>`
- `T DOWN <x> <y> <pointerId>`
- `T MOVE <x> <y> <pointerId>`
- `T UP <x> <y> <pointerId>`
- `K <androidKeyCode>`
- `PING`
- `BYE`

The Rust sender keeps its existing AAP touch decode and coordinate mapping.
