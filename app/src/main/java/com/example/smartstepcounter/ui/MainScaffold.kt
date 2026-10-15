package com.example.smartstepcounter.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

// Emoji icons avoid pulling in material-icons-extended.
private enum class Tab(val title: String, val icon: String) {
    SENSOR("Сенсор", "👣"),
    DEVICE("Пристрій", "📡"),
}

/**
 * Two-tab shell without navigation-compose: the selected tab is plain saveable state.
 * Each tab keeps its own Scaffold/TopAppBar; the insets used by the bottom bar
 * are consumed so the inner Scaffolds don't add them a second time.
 */
@Composable
fun MainScaffold(
    sensorTab: @Composable () -> Unit,
    deviceTab: @Composable () -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(Tab.SENSOR) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selected == tab,
                        onClick = { selected = tab },
                        icon = { Text(tab.icon) },
                        label = { Text(tab.title) },
                    )
                }
            }
        },
    ) { inner ->
        Box(
            Modifier
                .padding(bottom = inner.calculateBottomPadding())
                .consumeWindowInsets(inner)
        ) {
            when (selected) {
                Tab.SENSOR -> sensorTab()
                Tab.DEVICE -> deviceTab()
            }
        }
    }
}
