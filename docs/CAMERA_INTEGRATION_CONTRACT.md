# Camera integration: protected architecture contract

These invariants must be preserved by future feature work unless a deliberate replacement is approved and the guard script is updated in the same pull request.

## Required invariants

1. **Foco UI remains active on the normal phone camera route.** MainActivity routes the regular camera screen to FocoCameraIntegrationScreen. Keep the existing expanded/tablet Photon split layout intact.
2. **Photon owns camera hardware and lens switching.** FocoCameraIntegrationScreen adapts UI actions to Photon CameraViewModel. Zoom uses viewModel.setZoomRatio, camera flip uses viewModel.switchCamera(), and preview uses Photon CameraPreviewGL. Do not import Foco's CameraX engine or replace Photon physical-camera discovery, lens selection, zoom, preview, or video pipeline.
3. **There is one full settings destination.** Foco's quick settings sheet opens the merged, category-wise Photon settings page through actions.openPhotonSettings(). Do not reintroduce a separate Foco full-settings route.
4. **Video LUT shortcut stays available in Foco UI.** The video Palette/Edit control remains wired to Photon video LUT management.

## Change policy

- Add new camera features through the existing adapter boundary where possible.
- Do not copy or replace Photon lens-switch code to change UI.
- Any intentional change to an invariant must update this document and scripts/verify-camera-integration.sh in the same reviewed change.
- CI runs the guard before unit tests and APK assembly. A missing invariant fails the build.

## Protection limits

CI prevents accidental removal from being merged unnoticed; it does not make source files immutable. Repository administrators should also enable required pull requests, required status checks (including Build APK), and restrict direct pushes to main through GitHub branch protection/rulesets.
