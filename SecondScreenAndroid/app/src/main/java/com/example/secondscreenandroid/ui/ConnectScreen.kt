package com.example.secondscreenandroid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.secondscreenandroid.connection.ConnectionViewModel

@Composable
fun ConnectScreen(
    viewModel: ConnectionViewModel,
    onConnected: (host: String, port: Int) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("WiFi", "USB")

    val discoveredHosts by viewModel.discoveredHosts.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) }
                )
            }
        }

        when (selectedTab) {
            0 -> WiFiTab(
                discoveredHosts = discoveredHosts.map { Triple(it.name, it.host, it.port) },
                onConnected = onConnected
            )
            1 -> UsbTab(
                onConnect = {
                    onConnected("127.0.0.1", 7070)
                }
            )
        }
    }
}

@Composable
private fun WiFiTab(
    discoveredHosts: List<Triple<String, String, Int>>,
    onConnected: (host: String, port: Int) -> Unit
) {
    var manualIp by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Discovered Macs",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        if (discoveredHosts.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Searching for Mac...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(discoveredHosts) { (name, host, port) ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onConnected(host, port) }
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = name,
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "$host:$port",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Manual IP",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        OutlinedTextField(
            value = manualIp,
            onValueChange = { manualIp = it },
            label = { Text("IP address") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = {
                val ip = manualIp.trim()
                if (ip.isNotEmpty()) onConnected(ip, 7070)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = manualIp.trim().isNotEmpty()
        ) {
            Text("Connect")
        }
    }
}

@Composable
private fun UsbTab(onConnect: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Connect via USB",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Text(
                text = "1. Connect your Android to your Mac via USB\n" +
                        "2. On your Mac, run:\n   adb reverse tcp:7070 tcp:7070\n" +
                        "3. Tap Connect below",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
                textAlign = TextAlign.Start
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onConnect,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Connect via USB")
        }
    }
}
