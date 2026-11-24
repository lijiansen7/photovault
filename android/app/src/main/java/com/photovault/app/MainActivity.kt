package com.photovault.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.photovault.app.ui.PermsGate
import com.photovault.app.ui.browse.BrowseScreen
import com.photovault.app.ui.home.HomeScreen
import com.photovault.app.ui.settings.SettingsScreen
import com.photovault.app.ui.theme.PhotoVaultTheme
import com.photovault.app.ui.trash.TrashScreen

sealed class Tab(val route: String, val label: String, val icon: ImageVector) {
    data object Home : Tab("home", "备份", Icons.Default.CloudUpload)
    data object Browse : Tab("browse", "NAS 相册", Icons.Default.PhotoLibrary)
    data object Settings : Tab("settings", "设置", Icons.Default.Settings)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PhotoVaultTheme { AppRoot() } }
    }
}

@Composable
fun AppRoot() {
    PermsGate {
        val nav = rememberNavController()
        val tabs = listOf(Tab.Home, Tab.Browse, Tab.Settings)
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(26.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
                    tonalElevation = 1.dp,
                    shadowElevation = 12.dp,
                ) {
                    NavigationBar(
                        containerColor = Color.Transparent,
                        tonalElevation = 0.dp,
                    ) {
                        val entry by nav.currentBackStackEntryAsState()
                        val dest = entry?.destination
                        tabs.forEach { t ->
                            NavigationBarItem(
                                selected = dest?.hierarchy?.any { it.route == t.route } == true,
                                onClick = {
                                    nav.navigate(t.route) {
                                        popUpTo(Tab.Home.route) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(t.icon, contentDescription = t.label) },
                                label = { Text(t.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )
                        }
                    }
                }
            },
        ) { pad ->
            androidx.compose.foundation.layout.Box(
                Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.05f),
                                MaterialTheme.colorScheme.background,
                                MaterialTheme.colorScheme.background,
                            )
                        )
                    ),
            ) {
                NavHost(nav, startDestination = Tab.Home.route, modifier = Modifier.fillMaxSize()) {
                    composable(Tab.Home.route) {
                        HomeScreen(
                            onOpenSettings = {
                                nav.navigate(Tab.Settings.route) {
                                    popUpTo(Tab.Home.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            onOpenBrowse = {
                                nav.navigate(Tab.Browse.route) {
                                    popUpTo(Tab.Home.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                        )
                    }
                    composable(Tab.Browse.route) { BrowseScreen() }
                    composable(Tab.Settings.route) { SettingsScreen(onOpenTrash = { nav.navigate("trash") }) }
                    // 回收站：不在底部栏里，只从设置页进入
                    composable("trash") { TrashScreen() }
                }
            }
        }
    }
}
