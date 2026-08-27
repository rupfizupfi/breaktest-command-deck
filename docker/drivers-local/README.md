# docker/drivers-local/ — host-provided driver plugins

Mounted read-only into the deck container at `/app/drivers-local`, which is the second entry on the
container's `LOADER_PATH`. Drop a driver plugin jar here and restart the container; there is nothing
to rebuild.

This directory exists for exactly one jar: **`usbmodbus.jar`**, the CFW11 frequency-converter
plugin. It is licence-restricted — it bundles WEG's `CommunicationLib.jar` and Thesycon's
`ThesyconUSBLib.jar`, which may not be redistributed — so it is never committed and never baked
into an image. Build it in the sibling `../usbmodbus` repo (`./gradlew shadowJar`) and copy
`build/libs/usbmodbus.jar` here.

Do **not** put `dscusb.jar` here. The image already carries it at `/app/drivers`, staged from
GitHub Packages at build time; a second copy on `LOADER_PATH` would be a silent version-skew trap
(the first directory wins).

Without this jar the container starts only in the sense that it fails loudly:
`HardwareModeCheck` refuses `deck.hardware.mode=real` and names the missing `DriveProvider`. It
never falls back to a simulator. See `doc/03-backend/driver-jars.md` and
`doc/05-ops/docker-and-profiles.md`.
