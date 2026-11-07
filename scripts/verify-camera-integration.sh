#!/usr/bin/env bash
set -euo pipefail

require() {
  local file="$1" text="$2"
  if ! grep -Fq "$text" "$file"; then
    echo "::error file=$file::Protected Photon camera contract missing: $text"
    exit 1
  fi
}

# The app must route to PhotonCamera's original camera UI, not the removed Foco UI.
require "app/src/main/java/com/hinnka/mycamera/MainActivity.kt" "CameraScreen("
if grep -Fq "FocoCameraIntegrationScreen(" "app/src/main/java/com/hinnka/mycamera/MainActivity.kt"; then
  echo "::error::Removed Foco camera UI is still wired into MainActivity."
  exit 1
fi

# Keep the original Photon UI's instant standby-frame handoff path.
require "app/src/main/java/com/hinnka/mycamera/ui/camera/CameraScreen.kt" "viewModel.canInstantlySwitchLens(cameraId)"
require "app/src/main/java/com/hinnka/mycamera/ui/camera/CameraScreen.kt" "viewModel.switchToLens(cameraId)"
require "app/src/main/java/com/hinnka/mycamera/ui/camera/CameraScreen.kt" "viewModel.switchToLensAndSetZoomRatio(camera.cameraId, targetZoom)"
require "app/src/main/java/com/hinnka/mycamera/viewmodel/CameraViewModel.kt" "fun canInstantlySwitchLens(cameraId: String)"
require "app/src/main/java/com/hinnka/mycamera/viewmodel/CameraViewModel.kt" "previewView.hasStandbyPreviewFrame"
require "app/src/main/java/com/hinnka/mycamera/viewmodel/CameraViewModel.kt" "fun switchToLensAndSetZoomRatio(cameraId: String, ratio: Float)"
require "app/src/main/java/com/hinnka/mycamera/camera/Camera2Controller.kt" "StandbyCameraController(context)"
require "app/src/main/java/com/hinnka/mycamera/camera/StandbyCameraController.kt" "class StandbyCameraController(context: Context)"

echo "Original Photon UI and instant lens-switch protection checks passed."
