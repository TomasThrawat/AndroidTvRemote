package com.tomasthrawat.androidtvremote.ui

import android.app.Activity
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tomasthrawat.androidtvremote.data.PairedTv
import com.tomasthrawat.androidtvremote.data.PairedTvStore
import com.tomasthrawat.androidtvremote.discovery.DiscoveredTv
import com.tomasthrawat.androidtvremote.discovery.TvDiscovery
import com.tomasthrawat.androidtvremote.net.PairingSession
import com.tomasthrawat.androidtvremote.net.RemoteSession
import com.tomasthrawat.androidtvremote.proto.RemoteProto.RemoteKeyCode

private sealed class Screen {
    data object Discovering : Screen()
    data class EnteringCode(val tv: DiscoveredTv, val session: PairingSession) : Screen()
    data class Connected(val tvName: String, val remote: RemoteSession) : Screen()
}

/**
 * Top-level screen: discover TVs on the network, pair with one (the TV shows
 * a 6-character code, typed in here), then show the remote control. All
 * network I/O runs on plain background threads for this first version - see
 * README.md "known limitations". Discovery only runs while the user has
 * pressed the search box; it does not scan automatically on screen load.
 */
@Composable
fun RemoteApp(activity: Activity) {
    val store = remember { PairedTvStore(activity) }
    var screen by remember { mutableStateOf<Screen>(Screen.Discovering) }
    var searching by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf(listOf<DiscoveredTv>()) }
    var error by remember { mutableStateOf<String?>(null) }

    DisposableEffect(searching) {
        if (!searching) return@DisposableEffect onDispose {}
        val discovery = TvDiscovery(activity) { tv ->
            if (found.none { it.host == tv.host }) found = found + tv
        }
        discovery.start()
        onDispose { discovery.stop() }
    }

    when (val current = screen) {
        is Screen.Discovering -> DiscoveryList(
            searching = searching,
            found = found,
            error = error,
            onSearchClick = {
                found = emptyList()
                error = null
                searching = true
            }
        ) { tv ->
            error = null
            searching = false
            val session = PairingSession(tv.host)
            Thread {
                try {
                    session.start()
                    screen = Screen.EnteringCode(tv, session)
                } catch (e: Exception) {
                    error = "تعذر الاتصال بـ ${tv.name}: ${e.message}"
                }
            }.start()
        }

        is Screen.EnteringCode -> CodeEntry(current.tv.name) { code ->
            Thread {
                val ok = runCatching { current.session.submitCode(code) }.getOrDefault(false)
                current.session.close()
                if (ok) {
                    store.save(PairedTv(current.tv.name, current.tv.host))
                    val remote = RemoteSession(current.tv.host) {}
                    remote.connect()
                    screen = Screen.Connected(current.tv.name, remote)
                } else {
                    error = "الكود غير صحيح، حاول تاني"
                    screen = Screen.Discovering
                }
            }.start()
        }

        is Screen.Connected -> RemoteControls(current.tvName, current.remote)
    }
}

@Composable
private fun DiscoveryList(
    searching: Boolean,
    found: List<DiscoveredTv>,
    error: String?,
    onSearchClick: () -> Unit,
    onSelect: (DiscoveredTv) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("أجهزة التلفزيون على الشبكة", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(36.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline)
                    .clickable(enabled = !searching) { onSearchClick() },
                contentAlignment = Alignment.Center
            ) {
                Text("🔍")
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(8.dp))
        when {
            !searching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("اضغط على 🔍 للبحث عن أجهزة التلفزيون")
            }
            found.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            else -> LazyColumn {
                items(found) { tv ->
                    ListItem(
                        headlineContent = { Text(tv.name) },
                        supportingContent = { Text(tv.host) }
                    )
                    Button(onClick = { onSelect(tv) }, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                        Text("اقتران")
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeEntry(tvName: String, onSubmit: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("اكتب الكود اللي ظاهر على $tvName")
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(value = code, onValueChange = { code = it.take(6) }, label = { Text("الكود (6 أحرف)") })
        Spacer(Modifier.height(16.dp))
        Button(onClick = { onSubmit(code) }, enabled = code.length == 6) { Text("تأكيد") }
    }
}

@Composable
private fun RemoteControls(tvName: String, remote: RemoteSession) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("متصل بـ $tvName", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(24.dp))
        Row {
            RemoteButton("رجوع") { remote.sendKey(RemoteKeyCode.KEYCODE_BACK) }
            RemoteButton("الرئيسية") { remote.sendKey(RemoteKeyCode.KEYCODE_HOME) }
        }
        Spacer(Modifier.height(16.dp))
        RemoteButton("⬆") { remote.sendKey(RemoteKeyCode.KEYCODE_DPAD_UP) }
        Row {
            RemoteButton("⬅") { remote.sendKey(RemoteKeyCode.KEYCODE_DPAD_LEFT) }
            RemoteButton("تأكيد") { remote.sendKey(RemoteKeyCode.KEYCODE_DPAD_CENTER) }
            RemoteButton("➡") { remote.sendKey(RemoteKeyCode.KEYCODE_DPAD_RIGHT) }
        }
        RemoteButton("⬇") { remote.sendKey(RemoteKeyCode.KEYCODE_DPAD_DOWN) }
        Spacer(Modifier.height(24.dp))
        Row {
            RemoteButton("صوت -") { remote.sendKey(RemoteKeyCode.KEYCODE_VOLUME_DOWN) }
            RemoteButton("كتم") { remote.sendKey(RemoteKeyCode.KEYCODE_VOLUME_MUTE) }
            RemoteButton("صوت +") { remote.sendKey(RemoteKeyCode.KEYCODE_VOLUME_UP) }
        }
        Spacer(Modifier.height(24.dp))
        RemoteButton("تشغيل / إيقاف") { remote.sendKey(RemoteKeyCode.KEYCODE_POWER) }
    }
}

@Composable
private fun RemoteButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.padding(4.dp)) { Text(label) }
}
