package com.hinnka.mycamera.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import com.styropyr0.prismal.PrismalBackdrop

/** Backdrop supplied only while the Settings screen is rendered with Prismal glass. */
val LocalSettingsPrismalBackdrop = staticCompositionLocalOf<PrismalBackdrop?> { null }
