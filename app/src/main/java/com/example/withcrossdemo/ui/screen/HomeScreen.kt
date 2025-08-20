package com.example.withcrossdemo.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.example.withcrossdemo.ui.nav.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(navController: NavHostController) {
    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = { Text("WithCrossDemo") },
                navigationIcon = {
                    IconButton(
                        onClick = { navController.navigate(Screen.DeviceSetting.route) }
                    ) { Icon(Icons.Default.Menu, "settings") }
                }
            )
        }
    ) { inner ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentAlignment = Alignment.Center
        ) {
            Button(
                modifier = Modifier.padding(16.dp),
                onClick = { navController.navigate(Screen.BleSetup.route) }
            ) { Text("スタート") }
        }
    }
}
