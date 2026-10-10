package org.klippelgo.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import org.klippelgo.app.ui.KlippelGoApp
import org.klippelgo.app.ui.KlippelGoTheme

class MainActivity : AppCompatActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Portuguese is the default whatever the phone's language; English and Spanish are picked in the app.
        if (AppCompatDelegate.getApplicationLocales().isEmpty) setAppLanguage("pt-BR")

        // Scripted launches (scripts/two-phones.sh) pass the role and node address as extras.
        if (savedInstanceState == null) intent.getStringExtra("role")?.let { extra ->
            val role = if (extra.equals("driver", ignoreCase = true)) Role.DRIVER else Role.RIDER
            vm.connect(role, intent.getStringExtra("rpc") ?: vm.state.value.rpcUrl)
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
