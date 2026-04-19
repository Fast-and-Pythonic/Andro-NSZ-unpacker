package com.androNSZ.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.androNSZ.R
import com.androNSZ.ui.components.CompactCenterAlignedTopAppBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            CompactCenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.about_app_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(modifier = Modifier.height(8.dp)) }

            // App name and version
            item {
                Card {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "v1.0.0",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // What is this app
            item {
                InfoSection(
                    title = stringResource(R.string.about_what_is_title),
                    content = stringResource(R.string.about_what_is_content)
                )
            }

            // Supported formats
            item {
                InfoSection(
                    title = stringResource(R.string.about_formats_title),
                    content = stringResource(R.string.about_formats_content)
                )
            }

            // Format details
            item {
                Card {
                    Column(modifier = Modifier.padding(16.dp)) {
                        FormatItem(
                            inputFormat = "NSZ",
                            outputFormat = "NSP",
                            description = stringResource(R.string.about_nsz_description)
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                        FormatItem(
                            inputFormat = "XCZ",
                            outputFormat = "XCI",
                            description = stringResource(R.string.about_xcz_description)
                        )
                    }
                }
            }

            // How it works
            item {
                InfoSection(
                    title = stringResource(R.string.about_how_works_title),
                    content = stringResource(R.string.about_how_works_content)
                )
            }

            // Requirements
            item {
                InfoSection(
                    title = stringResource(R.string.about_requirements_title),
                    content = stringResource(R.string.about_requirements_content)
                )
            }

            // Credits
            item {
                Card {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.about_credits_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.about_credits_content),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun InfoSection(
    title: String,
    content: String
) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = content,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun FormatItem(
    inputFormat: String,
    outputFormat: String,
    description: String
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = inputFormat,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "converts to",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = outputFormat,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
