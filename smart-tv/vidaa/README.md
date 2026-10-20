# Hisense VIDAA

VIDAA development and app distribution vary significantly by TV model, region and firmware. This directory intentionally starts with a validation checklist rather than pretending that a generic web package will install on every Hisense TV.

## Before implementation

1. Identify the exact TV model, region, VIDAA OS version and firmware build.
2. Confirm whether official VIDAA developer/partner access is available for that device.
3. Obtain the applicable official web-app guide and SDK, if available.
4. Confirm supported HTML/CSS/JavaScript features, package format, app launch APIs, signing and installation procedure.
5. Only then add the model-appropriate manifest, launch adapter and build pipeline.

The shared UI is the baseline to port. App launch, system Home replacement, Home-key interception and power-on autostart are **unverified** until demonstrated on the exact model and firmware. Do not assume Tizen or webOS package metadata can be reused for VIDAA.
