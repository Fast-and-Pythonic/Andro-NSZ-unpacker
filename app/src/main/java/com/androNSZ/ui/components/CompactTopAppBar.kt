package com.androNSZ.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactCenterAlignedTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    colors: TopAppBarColors = TopAppBarDefaults.centerAlignedTopAppBarColors(),
    appBarHeight: Dp = 42.dp,
    statusBarTopPadding: Dp? = 40.dp,
) {
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val topPadding = statusBarTopPadding ?: statusBarHeight
    
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(appBarHeight + statusBarHeight),
        color = colors.containerColor,
        contentColor = colors.titleContentColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = topPadding,
                    start = 4.dp,
                    end = 4.dp
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Navigation Icon
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                navigationIcon()
            }

            // Title
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                ProvideTextStyle(value = MaterialTheme.typography.titleLarge) {
                    title()
                }
            }

            // Actions
            Row(
                modifier = Modifier.wrapContentWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                actions()
            }
        }
    }
}
