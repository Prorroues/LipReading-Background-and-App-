package com.rokid.cxrmsamples.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class Tab {
    ASSISTANT,
    HOME,
    GALLERY,
    MINE
}

data class TabItem(
    val tab: Tab,
    val label: String,
    val icon: ImageVector
)

@Composable
fun BottomNavigationBar(
    currentTab: Tab,
    onTabSelected: (Tab) -> Unit,
    modifier: Modifier = Modifier
) {
    val tabs = listOf(
        TabItem(Tab.ASSISTANT, "助手", Icons.AutoMirrored.Filled.Chat),
        TabItem(Tab.HOME, "主页", Icons.Default.Home),
        TabItem(Tab.GALLERY, "相册", Icons.Default.Image),
        TabItem(Tab.MINE, "我的", Icons.Default.Person)
    )

    NavigationBar(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        tabs.forEach { tabItem ->
            NavigationBarItem(
                selected = currentTab == tabItem.tab,
                onClick = { onTabSelected(tabItem.tab) },
                icon = {
                    Icon(
                        imageVector = tabItem.icon,
                        contentDescription = tabItem.label
                    )
                },
                label = {
                    Text(
                        text = tabItem.label,
                        fontSize = 10.sp
                    )
                }
            )
        }
    }
}
