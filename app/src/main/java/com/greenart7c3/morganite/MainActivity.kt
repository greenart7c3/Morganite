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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.greenart7c3.morganite.ui.theme.MorganiteTheme
import kotlinx.coroutines.launch
import java.text.DecimalFormat
import kotlin.text.format

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
                    val isRunning = Morganite.instance.httpServer.isRunning.collectAsStateWithLifecycle()

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        val storageSize = Morganite.instance.httpServer.fileStore.size.collectAsStateWithLifecycle()
                        Text(stringResource(R.string.storage_used_mb, DecimalFormat("#.###").format(storageSize.value / (1024.0 * 1024.0))))
                        if (isRunning.value) {
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
                    }
                }
            }
        }
    }
}
