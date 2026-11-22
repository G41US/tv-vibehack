#!/usr/bin/env python3
"""Stage the shared TV VibeHack UI for platform SDK packaging.

This script does not sign or build native TV packages. Use each vendor's official
SDK/CLI after staging, and test on the target model and firmware.
"""
from pathlib import Path
import json
import shutil

ROOT = Path(__file__).resolve().parents[1]
SHARED = ROOT / "shared"
OUT = ROOT / "dist"
ASSETS = ("index.html", "style.css", "app.js")


def copy_shared(destination: Path) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    for filename in ASSETS:
        shutil.copy2(SHARED / filename, destination / filename)


def stage_tizen() -> None:
    dest = OUT / "tizen"
    copy_shared(dest)
    shutil.copy2(ROOT / "tizen" / "config.xml", dest / "config.xml")


def stage_webos() -> None:
    dest = OUT / "webos"
    copy_shared(dest)
    shutil.copy2(ROOT / "webos" / "appinfo.json", dest / "appinfo.json")


def stage_vidaa() -> None:
    dest = OUT / "vidaa"
    copy_shared(dest)
    (dest / "README-NEXT-STEPS.txt").write_text(
        "VIDAA package metadata and signing depend on the target TV model, firmware, "
        "and developer access. Confirm the official SDK and installation route before "
        "creating a distributable package.\n",
        encoding="utf-8",
    )


def main() -> None:
    if not SHARED.is_dir():
        raise SystemExit(f"Shared UI not found: {SHARED}")
    if OUT.exists():
        shutil.rmtree(OUT)
    stage_tizen()
    stage_webos()
    stage_vidaa()
    print(f"Staged platform folders in {OUT}")
    print("Next: use the vendor SDK/CLI to validate, sign, and package each platform.")
    print("No TV firmware, protected partition, or system settings were modified.")


if __name__ == "__main__":
    main()
