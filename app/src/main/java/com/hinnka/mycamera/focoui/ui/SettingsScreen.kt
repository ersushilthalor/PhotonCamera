package com.hinnka.mycamera.focoui.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VideoCameraBack
import androidx.compose.material.icons.outlined.Visibility
import com.hinnka.mycamera.ui.components.ScaledIcon as Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hinnka.mycamera.R
import com.hinnka.mycamera.focoui.settings.AppSettings
import com.hinnka.mycamera.focoui.settings.GridSetting
import com.hinnka.mycamera.focoui.settings.SettingKey
import com.hinnka.mycamera.focoui.settings.VolumeKeyAction

const val SOURCE_URL = "https://github.com/RealKHFan/foco"

private val SettingsBackground = Color(0xFF34372F)
private val SettingsCard = Color(0xFF4D5049)
private val SettingsSecondaryText = Color(0xFFE6E7E1)

private enum class SettingsCategory(
    val title: Int,
    val summary: Int,
    val icon: ImageVector,
    val iconBackground: Color,
    val iconTint: Color,
) {
    PHOTO_CAPTURE(
        R.string.foco_settings_photo_capture,
        R.string.foco_settings_photo_capture_summary,
        Icons.Outlined.CameraAlt,
        Color(0xFF9BE5F0),
        Color(0xFF07566A),
    ),
    PHOTO_PROCESSING(
        R.string.foco_settings_photo_processing,
        R.string.foco_settings_photo_processing_summary,
        Icons.Outlined.AutoAwesome,
        Color(0xFFE8C9F8),
        Color(0xFF56316E),
    ),
    VIDEO(
        R.string.foco_settings_video,
        R.string.foco_settings_video_summary,
        Icons.Outlined.VideoCameraBack,
        Color(0xFFF3C5CC),
        Color(0xFF6C2633),
    ),
    STORAGE(
        R.string.foco_settings_storage,
        R.string.foco_settings_storage_summary,
        Icons.Outlined.Storage,
        Color(0xFF9BE5F0),
        Color(0xFF07566A),
    ),
    CONTROLS(
        R.string.foco_settings_controls,
        R.string.foco_settings_controls_summary,
        Icons.Outlined.TouchApp,
        Color(0xFFFFDE78),
        Color(0xFF664900),
    ),
    VIEWFINDER(
        R.string.foco_settings_viewfinder,
        R.string.foco_settings_viewfinder_summary,
        Icons.Outlined.Visibility,
        Color(0xFFB7E7B9),
        Color(0xFF20572B),
    ),
    DIAGNOSTICS(
        R.string.foco_settings_diagnostics,
        R.string.foco_settings_diagnostics_summary,
        Icons.Outlined.BugReport,
        Color(0xFFBFD9FA),
        Color(0xFF234A78),
    ),
    ADVANCED(
        R.string.foco_settings_advanced,
        R.string.foco_settings_advanced_summary,
        Icons.Outlined.Tune,
        Color(0xFFF5D6C4),
        Color(0xFF70452F),
    ),
}

/** Foco settings hub, styled after the compact category list in the supplied reference. */
@Composable
fun SettingsScreen(state: AppState, settings: AppSettings, actions: CameraActions) {
    var selectedCategory by rememberSaveable { mutableStateOf<SettingsCategory?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }

    BackHandler {
        if (selectedCategory != null) {
            selectedCategory = null
        } else {
            state.fullSettingsOpen = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(SettingsBackground)
            .navigationBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 20.dp, top = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                if (selectedCategory != null) selectedCategory = null
                else state.fullSettingsOpen = false
            }) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Color.White)
            }
            Text(
                text = selectedCategory?.let { stringResource(it.title) } ?: stringResource(R.string.settings_title),
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        if (selectedCategory == null) {
            SettingsSearchField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(top = 14.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                val categories = SettingsCategory.entries.filter { category ->
                    val title = stringResource(category.title)
                    val summary = stringResource(category.summary)
                    searchQuery.isBlank() ||
                        title.contains(searchQuery.trim(), ignoreCase = true) ||
                        summary.contains(searchQuery.trim(), ignoreCase = true)
                }
                categories.forEach { category ->
                    SettingsCategoryRow(category) { selectedCategory = category }
                }
                if (categories.isEmpty()) {
                    Text(
                        text = stringResource(R.string.foco_settings_no_results),
                        color = SettingsSecondaryText.copy(alpha = 0.8f),
                        fontSize = 15.sp,
                        modifier = Modifier.padding(20.dp),
                    )
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (selectedCategory) {
                    SettingsCategory.PHOTO_CAPTURE -> {
                        SwitchRow(
                            "Save location",
                            "Add where photos were taken to photo metadata",
                            settings.saveLocation,
                        ) {
                            settings.saveLocation = it
                            actions.settingChanged(SettingKey.LOCATION)
                        }
                        SwitchRow("Camera sounds", "Shutter and recording sounds", settings.shutterSound) {
                            settings.shutterSound = it
                            actions.settingChanged(SettingKey.SHUTTER_SOUND)
                        }
                        SwitchRow("Mirror selfies", "Save front-camera photos the way the preview shows them", settings.mirrorFront) {
                            settings.mirrorFront = it
                            actions.settingChanged(SettingKey.MIRROR_FRONT)
                        }
                    }

                    SettingsCategory.PHOTO_PROCESSING -> {
                        SwitchRow(
                            "Foco processing",
                            "Merges multiple frames for HDR, Night and Ultra HDR photos. Off uses a single camera frame",
                            settings.processing,
                        ) {
                            settings.processing = it
                            actions.settingChanged(SettingKey.PROCESSING)
                        }
                        SwitchRow(
                            "Sharper zoom",
                            "Extra frames and detail when zoomed in past a physical lens",
                            settings.zoomDetail,
                            enabled = settings.processing,
                        ) { settings.zoomDetail = it }
                        SwitchRow(
                            "Dirty lens warning",
                            "Warn when the camera lens may be smudged",
                            settings.dirtyLens,
                        ) { settings.dirtyLens = it }
                        val ultraHdrPossible = state.caps.ultraHdrSupported ||
                            (settings.processing && android.os.Build.VERSION.SDK_INT >= 34)
                        SwitchRow(
                            "Ultra HDR",
                            if (ultraHdrPossible) "Brighter highlights on HDR screens" else "Requires Foco processing and Android 14",
                            settings.ultraHdr && ultraHdrPossible,
                            enabled = ultraHdrPossible,
                        ) {
                            settings.ultraHdr = it
                            actions.settingChanged(SettingKey.ULTRA_HDR)
                        }
                    }

                    SettingsCategory.VIDEO -> {
                        InfoRow(
                            "Video settings",
                            "Resolution, frame rate, recording format and stabilization",
                            onClick = { state.fullSettingsOpen = false; actions.openPhotonSettings() },
                        )
                        InfoRow(
                            "Video LUTs",
                            "Manage the looks used while recording video",
                            onClick = { state.fullSettingsOpen = false; actions.openPhotonFilters() },
                        )
                    }

                    SettingsCategory.STORAGE -> {
                        InfoRow(
                            "Photo and video storage",
                            "Save paths, export formats and automatic saving",
                            onClick = { state.fullSettingsOpen = false; actions.openPhotonSettings() },
                        )
                        InfoRow(
                            "Backup and restore",
                            "Back up or restore camera settings",
                            onClick = { state.fullSettingsOpen = false; actions.openPhotonSettings() },
                        )
                    }

                    SettingsCategory.CONTROLS -> {
                        SwitchRow(
                            "Show quick access controls",
                            "One-tap flash, timer and aspect-ratio controls",
                            settings.quickAccess,
                        ) { settings.quickAccess = it }
                        SwitchRow(
                            "Vibration",
                            "Haptic feedback for buttons and sliders",
                            settings.haptics,
                        ) {
                            settings.haptics = it
                            actions.settingChanged(SettingKey.HAPTICS)
                        }
                        ChoiceRow(
                            "Volume keys",
                            listOf("Shutter", "Zoom", "Volume"),
                            VolumeKeyAction.entries.indexOf(settings.volumeKeys),
                        ) {
                            settings.volumeKeys = VolumeKeyAction.entries[it]
                            actions.settingChanged(SettingKey.VOLUME_KEYS)
                        }
                    }

                    SettingsCategory.VIEWFINDER -> {
                        SwitchRow(
                            "Framing hints",
                            "Show a level line while taking photos",
                            settings.level,
                        ) {
                            settings.level = it
                            actions.settingChanged(SettingKey.LEVEL)
                        }
                        ChoiceRow(
                            "Grid type",
                            listOf("None", "3 × 3", "4 × 4", "Golden"),
                            GridSetting.entries.indexOf(settings.grid),
                        ) {
                            settings.grid = GridSetting.entries[it]
                            actions.settingChanged(SettingKey.GRID)
                        }
                        SwitchRow(
                            "Scan QR codes",
                            "Show links and text detected in the viewfinder",
                            settings.qrDetection,
                        ) {
                            settings.qrDetection = it
                            actions.settingChanged(SettingKey.QR)
                        }
                    }

                    SettingsCategory.DIAGNOSTICS -> {
                        InfoRow(
                            "Reports and logs",
                            "Open camera diagnostics and log reports",
                            onClick = { state.fullSettingsOpen = false; actions.openPhotonSettings() },
                        )
                        InfoRow(
                            "Diagnostic tools",
                            "Open advanced camera tools",
                            onClick = { state.fullSettingsOpen = false; actions.openPhotonToolbox() },
                        )
                    }

                    SettingsCategory.ADVANCED -> {
                        InfoRow(
                            "Photon camera settings",
                            "Open the complete camera settings and expert options",
                            onClick = { state.fullSettingsOpen = false; actions.openPhotonSettings() },
                        )
                        InfoRow(
                            "LUTs and filters",
                            "Manage photo and video looks",
                            onClick = { state.fullSettingsOpen = false; actions.openPhotonFilters() },
                        )
                        InfoRow(
                            "Frames",
                            "Open Photon frame management",
                            onClick = { state.fullSettingsOpen = false; actions.openPhotonFrames() },
                        )
                        InfoRow(
                            "Presets",
                            "Open Photon preset management",
                            onClick = { state.fullSettingsOpen = false; actions.openPhotonPresets() },
                        )
                        InfoRow(
                            "Advanced tools",
                            "Open camera tools and developer options",
                            onClick = { state.fullSettingsOpen = false; actions.openPhotonToolbox() },
                        )
                        SwitchRow(
                            "Remove phone model from photos",
                            "Strip make, model and lens details from photo metadata",
                            settings.stripDeviceInfo,
                        ) { settings.stripDeviceInfo = it }
                        InfoRow("No internet access", "Foco cannot connect to the internet. Nothing leaves your phone.")
                        InfoRow("Foco + Photon Camera", "Foco interface with the Photon camera engine")
                        InfoRow(
                            "Source code",
                            SOURCE_URL.removePrefix("https://"),
                            onClick = { actions.openUrl(SOURCE_URL) },
                        )
                    }

                    null -> Unit
                }
            }
        }
    }
}

@Composable
private fun SettingsSearchField(value: String, onValueChange: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(54.dp)
            .clip(CircleShape)
            .background(SettingsCard)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, tint = Color.White, modifier = Modifier.size(24.dp))
        Spacer(Modifier.size(14.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
            decorationBox = { innerTextField ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            stringResource(R.string.foco_settings_search),
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 16.sp,
                        )
                    }
                    innerTextField()
                }
            },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SettingsCategoryRow(category: SettingsCategory, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SettingsCard)
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(category.iconBackground),
            contentAlignment = Alignment.Center,
        ) {
            Icon(category.icon, null, tint = category.iconTint, modifier = Modifier.size(22.dp))
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 15.dp, end = 8.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                stringResource(category.title),
                color = Color.White,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 23.sp,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(category.summary),
                color = SettingsSecondaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 19.sp,
            )
        }
        Icon(
            Icons.Outlined.ChevronRight,
            null,
            tint = Color.White,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SettingsCard)
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, color = if (enabled) Color.White else SettingsSecondaryText.copy(alpha = 0.55f), fontSize = 17.sp)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = SettingsSecondaryText, fontSize = 14.sp, lineHeight = 19.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun ChoiceRow(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SettingsCard)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(title, color = Color.White, fontSize = 17.sp)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { index, option ->
                val isSelected = index == selected
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(22.dp))
                        .background(if (isSelected) Color(0xFFD5E4E0) else Color(0xFF3B3E38))
                        .clickable { onSelect(index) }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        option,
                        color = if (isSelected) Color(0xFF26332F) else Color.White,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoRow(title: String, subtitle: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SettingsCard)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = SettingsSecondaryText, fontSize = 14.sp, lineHeight = 19.sp)
        }
        if (onClick != null) {
            Icon(Icons.Outlined.ChevronRight, null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
    }
}
