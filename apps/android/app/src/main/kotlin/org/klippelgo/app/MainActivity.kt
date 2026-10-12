package org.klippelgo.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import org.klippelgo.app.ui.KlippelGoApp
import org.klippelgo.app.ui.KlippelGoTheme

class MainActivity : AppCompatActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Portuguese is the default whatever the phone's language; English and Spanish are picked in the app.
        if (AppCompatDelegate.getApplicationLocales().isEmpty) setAppLanguage("pt-BR")

        // Scripted launches (scripts/two-phones.sh) pass the role the phone starts in, the node address and which
        // pair of phones it belongs to; role and pair together pick the phone's account.
        if (savedInstanceState == null) intent.getStringExtra("role")?.let { extra ->
            val role = if (extra.equals("driver", ignoreCase = true)) Role.DRIVER else Role.RIDER
            val rpc = intent.getStringExtra("rpc") ?: vm.state.value.rpcUrl
            val pair = intent.getIntExtra("pair", 0)
            vm.connect(role, rpc, pair.takeIf { it in 1..2 })
        }

        setContent {
            KlippelGoTheme {
                KlippelGoApp(vm)
            }
        }
    }
}

fun setAppLanguage(tag: String) {
    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
}
