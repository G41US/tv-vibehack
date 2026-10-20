# LG webOS

This folder contains starter metadata. Before packaging, create a webOS TV app project using the official LG webOS TV SDK/CLI and copy the shared UI into the app's package root as `index.html`, `style.css` and `app.js`. The metadata and icon filenames must exist in the final package.

## Build/test

1. Install the official webOS TV SDK / CLI for the target webOS generation.
2. Create/import the web app project, copy the shared assets and validate app metadata.
3. Package and install using the supported Developer Mode workflow.
4. Test D-pad navigation, Back behavior, focus visibility and app exit on the exact TV.
5. Record test results in ../TESTING.md.

An ordinary sideloaded webOS app does not gain global Home-key interception or boot autostart merely by being installed. Do not claim it is a full system Home replacement. Rooted-TV custom Home projects exist in the community, but are model/firmware-specific and are outside this supported package.
