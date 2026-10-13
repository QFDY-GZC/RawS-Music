# Minimal HCT source dependency

Unmodified files from Google's Material Components Android **1.13.0** sources artifact:
`com.google.android.material:material:1.13.0:sources`.

Only Hct, Cam16, HctSolver, ColorUtils, MathUtils and ViewingConditions are needed by
ClassicProgressColor's tone conversion. Retaining these exact classes removes the full
Material Views AAR and its resources without substituting a different color model.

Upstream: https://github.com/material-components/material-components-android/tree/1.13.0/lib/java/com/google/android/material/color/utilities

License: Apache-2.0; original copyright headers remain in each file. Full license is
at `core/ui/third-party/material-color/LICENSE`.

Source artifact SHA-256 is recorded in the APK size analysis document. Compare these
files against the pinned sources artifact when updating; never update them silently.
