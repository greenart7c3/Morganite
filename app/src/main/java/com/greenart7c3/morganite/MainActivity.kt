package com.greenart7c3.morganite

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.greenart7c3.morganite.ui.theme.MorganiteTheme
import kotlinx.coroutines.launch
import java.text.DecimalFormat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MorganiteTheme {
                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission(),
                    onResult = { isGranted: Boolean ->
                        Log.d("MainActivity", "Permission granted: $isGranted")
                    }
                )

                LaunchedEffect(Unit) {
                    permissionLauncher.launch("android.permission.POST_NOTIFICATIONS")
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val isRunning by Morganite.instance.httpServer.isRunning.collectAsStateWithLifecycle()
                    val settings by Morganite.instance.settingsManager.settings.collectAsStateWithLifecycle()
                    val logStream by Morganite.instance.logStream.collectAsStateWithLifecycle()

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val storageSize by Morganite.instance.httpServer.fileStore.size.collectAsStateWithLifecycle()
                        Text(stringResource(R.string.storage_used_mb, DecimalFormat("#.###").format(storageSize / (1024.0 * 1024.0))))

                        Spacer(modifier = Modifier.height(16.dp))

                        if (isRunning) {
                            ElevatedButton(
                                onClick = {
                                    Morganite.instance.scope.launch {
                                        Morganite.instance.httpServer.stop()
                                    }
                                },
                                content = { Text(stringResource(R.string.stop)) },
                            )
                        } else {
                            ElevatedButton(
                                onClick = {
                                    Morganite.instance.scope.launch {
                                        Morganite.instance.httpServer.start()
                                    }
                                },
                                content = { Text(stringResource(R.string.start)) },
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(stringResource(R.string.use_tor))
                            Switch(
                                checked = settings.useTor,
                                onCheckedChange = {
                                    Morganite.instance.settingsManager.updateUseTor(it)
                                }
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(stringResource(R.string.use_tor_for_all_urls))
                            Switch(
                                enabled = settings.useTor,
                                checked = settings.useTorForAllUrls,
                                onCheckedChange = {
                                    Morganite.instance.settingsManager.updateUseTorForAllUrls(it)
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(16.dp))

                        val lazyListState = rememberLazyListState()
                        LaunchedEffect(logStream.size) {
                            if (logStream.isNotEmpty()) {
                                lazyListState.scrollToItem(logStream.size - 1)
                            }
                        }

                        LazyColumn(
                            state = lazyListState,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(logStream) { line ->
                                Text(
                                    text = line,
                                    fontSize = 12.sp,
                                    lineHeight = 14.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
