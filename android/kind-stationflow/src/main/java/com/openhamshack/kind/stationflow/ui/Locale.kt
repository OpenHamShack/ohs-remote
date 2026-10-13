package com.openhamshack.kind.stationflow.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * The locale ShackFormat formats in, as a BCP 47 tag: the one Compose is
 * drawing with, so a frequency's decimal separator always matches the
 * language of the words around it (stringResource reads the same
 * configuration). Read per composition rather than once at start, so a
 * language change in the system settings reaches the numbers too.
 */
@Composable
@ReadOnlyComposable
internal fun shackLocale(): String = LocalConfiguration.current.locales.get(0).toLanguageTag()
