# Samsung Tizen

This folder is a packaging starting point, not a tested WGT release.

## Build/test

1. Install the official Samsung TV SDK / Tizen Studio components appropriate to the target TV generation.
2. Import this directory as a Tizen TV Web application, adjusting metadata and paths to match the installed SDK.
3. Package and sign with a certificate profile accepted by the TV.
4. Enable Developer Mode on the TV, connect it to the development computer, install the WGT, and test remote navigation.
5. Record model, Tizen version, install path and test results in ../TESTING.md.

The shared app currently demonstrates UI and D-pad navigation only. It does not yet contain a verified Tizen app-launch adapter.

**Limits:** a normal third-party Tizen app should not be advertised as replacing the Samsung system Home screen, capturing Home globally, or starting automatically at power-on. These behaviors are not assumed here.
