package com.xayah.feature.main.restore

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ManageSearch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xayah.core.model.StorageMode
import com.xayah.core.ui.component.Clickable
import com.xayah.core.ui.component.LocalSlotScope
import com.xayah.core.ui.component.PackageIcons
import com.xayah.core.ui.component.Selectable
import com.xayah.core.ui.component.Title
import com.xayah.core.ui.component.paddingBottom
import com.xayah.core.ui.component.paddingHorizontal
import com.xayah.core.ui.component.paddingTop
import com.xayah.core.ui.component.select
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.ui.util.LocalNavController
import com.xayah.core.ui.util.icon
import com.xayah.core.util.navigateSingle

@SuppressLint("StringFormatInvalid")
@ExperimentalFoundationApi
@ExperimentalLayoutApi
@ExperimentalAnimationApi
@ExperimentalMaterial3Api
@Composable
fun PageRestore() {
    val navController = LocalNavController.current!!
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val viewModel = hiltViewModel<IndexViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lastRestoreTime by viewModel.lastRestoreTimeState.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()

    LaunchedEffect(null) {
        viewModel.launchOnIO {
            viewModel.emitIntent(IndexUiIntent.UpdateApps)
            viewModel.emitIntent(IndexUiIntent.UpdateFiles)
        }
    }

    RestoreScaffold(
        scrollBehavior = scrollBehavior,
        title = stringResource(id = R.string.restore),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            OverviewLastRestoreCard(
                modifier = Modifier.padding(SizeTokens.Level16),
                lastRestoreTime = lastRestoreTime
            )

            val appsInteractionSource = remember { MutableInteractionSource() }
            Clickable(
                title = stringResource(id = R.string.apps),
                value = if (uiState.packages.isEmpty()) null else
                    "${context.getString(R.string.args_apps_backed_up, uiState.packages.size)}${if (uiState.packagesSize.isNotEmpty()) " (${uiState.packagesSize})" else ""}",
                leadingIcon = ImageVector.vectorResource(id = R.drawable.ic_rounded_apps),
                interactionSource = appsInteractionSource,
                content = if (uiState.packages.isEmpty()) null else {
                    {
                        PackageIcons(modifier = Modifier.paddingTop(SizeTokens.Level8), packages = uiState.packages)
                    }
                }
            ) {
                viewModel.emitIntentOnIO(IndexUiIntent.ToAppList(navController))
            }

            Title(title = stringResource(id = R.string.advanced)) {
                Clickable(
                    title = stringResource(id = R.string.reload),
                    value = stringResource(id = R.string.reload_desc),
                    leadingIcon = Icons.Rounded.ManageSearch,
                ) {
                    viewModel.emitIntentOnIO(IndexUiIntent.ToReload(navController))
                }
            }
        }
    }
}
