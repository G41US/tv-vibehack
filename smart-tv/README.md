# TV VibeHack — non-Android Smart TV ports

This directory adds a separate web-app implementation for **Samsung Tizen**, **LG webOS**, and **Hisense VIDAA**. It does not change the existing Android/Airtel Smart Box launcher or its release workflow.

## Important platform boundary

A regular sideloaded app is not the same thing as a system launcher. On retail TVs, third-party apps generally cannot replace the system Home screen, intercept the physical Home key globally, or auto-start at power-on through public app APIs. Those capabilities depend on the exact OS, model, firmware, developer/partner access, and sometimes unsupported root access. This project will report these as capabilities, not promise them universally.

## Layout

- `shared/` — remote-friendly web UI and app catalogue contract
- `tizen/` — Samsung Tizen package metadata and packaging notes
- `webos/` — LG webOS app metadata and packaging notes
- `vidaa/` — VIDAA discovery and packaging notes
- `CAPABILITY-MATRIX.md` — what each port can and cannot promise

## Development

The shared UI is a static HTML/CSS/JavaScript app and can be previewed locally by opening `shared/index.html`. On a TV, package it with that platform's official developer tools and test on the exact model.

This is a starter port, not a claim that all three TV platforms have been tested. TV app launch APIs, installation rules and app-store eligibility differ by firmware. No protected system partition is modified.

## Recommended order

1. Build and test the shared UI in a browser with keyboard/remote navigation.
2. Package and test Tizen on a real Samsung TV or emulator.
3. Package and test webOS on a real LG TV or emulator.
4. Validate VIDAA developer access and installation on the target Hisense model before promising a distributable build.
5. Record exact model, OS version, firmware, install method and test results in `TESTING.md`.

The Android/Airtel implementation remains the stable, separate edition in the repository root.
