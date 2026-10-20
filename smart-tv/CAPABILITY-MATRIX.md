# Smart TV capability matrix

This matrix is deliberately conservative. Exact behavior must be confirmed against the TV model and firmware.

| Capability | Samsung Tizen | LG webOS | Hisense VIDAA |
|---|---|---|---|
| Build a standalone app | Yes, using Tizen tooling | Yes, using webOS tooling | Depends on model, SDK/developer access and firmware |
| Remote-control navigation in our app | Implementable | Implementable | Implementable in a compatible web app |
| Launch supported apps from our UI | Depends on Tizen app/service APIs and target app | Depends on webOS app APIs and target app | Depends on VIDAA APIs and target app |
| Replace system Home screen using an ordinary app | Not generally supported | Not generally supported | Not established as a general public capability |
| Globally capture physical Home key | Do not assume available | Do not assume available | Do not assume available |
| Auto-start at power-on | Model/API-specific; not guaranteed | Not generally available to ordinary third-party apps | Must be verified per model/partner APIs |
| Public, self-service app installation | Developer mode/sideloading rules apply | Developer Mode supports development installs; app-store rules apply | May require VIDAA partner access or a firmware-specific debug flow |

## Product promise

The first milestone is a **TV VibeHack-style home dashboard that runs as an app**, not a guaranteed system-level replacement. Where a TV blocks Home-key takeover or startup, keep the original Home screen intact and offer a clear instruction to launch TV VibeHack from the TV's app list.

Do not disable stock system apps, write to protected partitions, exploit firmware, or instruct users to root a TV as part of the standard installer. Any later experimental, model-specific research must be isolated from the supported build and include recovery instructions.
