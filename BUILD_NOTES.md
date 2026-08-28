# Build verification notes

The source tree and build workflow are complete. This ChatGPT execution container does not contain Android SDK / Gradle / Android build-tools and its shell network cannot resolve external artifact hosts, so an APK cannot be truthfully claimed as locally compiled inside this container.

The included GitHub Actions workflow is the reproducible build path. It downloads the official KWS model and builds a signed debug APK.
