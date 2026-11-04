#!/usr/bin/env bash
set -euo pipefail
require() {
  local file="$1" text="$2"
  if ! grep -Fq "$text" "$file"; then
    echo "::error file=$file::Protected camera integration contract missing: $text"
    exit 1
  fi
}
# Normal phone camera route must keep the Foco UI.
require "app/src/main/java/com/hinnka/mycamera/MainActivity.kt" "FocoCameraIntegrationScreen("
# Photon remains the camera/lens engine behind the Foco UI.
require "app/src/main/java/com/hinnka/mycamera/ui/camera/FocoCameraIntegrationScreen.kt" "viewModel.setZoomRatio("
require "app/src/main/java/com/hinnka/mycamera/ui/camera/FocoCameraIntegrationScreen.kt" "CameraPreviewGL("
require "app/src/main/java/com/hinnka/mycamera/ui/camera/FocoCameraIntegrationScreen.kt" "viewModel.switchCamera()"
# One merged full-settings destination.
require "app/src/main/java/com/hinnka/mycamera/focoui/ui/SettingsSheet.kt" "actions.openPhotonSettings()"
require "app/src/main/java/com/hinnka/mycamera/ui/settings/SettingsScreen.kt" "MergedSettingsCategoryTitle"
# Photon video LUT shortcut remains visible in Foco video UI.
require "app/src/main/java/com/hinnka/mycamera/focoui/ui/CameraScreen.kt" "Edit video LUT"
require "app/src/main/java/com/hinnka/mycamera/focoui/ui/AppState.kt" "fun openVideoLutEditor()"
echo "Camera integration protection checks passed."
