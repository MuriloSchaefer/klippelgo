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
        // pair of phones it belongs to; role and pair together pick one of the local testnet's seeded accounts.
        // The end-to-end tests ask for those accounts with `devAccounts`, and then go through the create-account
        // screen; with `e2eAccounts` they get their own set of them. All work in debug builds only: the activity is
        // exported, and in a release build any app on the phone could otherwise point KlippelGo at an RPC it runs.
        if (savedInstanceState == null && BuildConfig.DEBUG) {
            val e2e = intent.getBooleanExtra("e2eAccounts", false)
            if (intent.getBooleanExtra("devAccounts", false)) vm.useDevAccounts(true, e2e)
            intent.getStringExtra("role")?.let { extra ->
                val role = if (extra.equals("driver", ignoreCase = true)) Role.DRIVER else Role.RIDER
                val rpc = intent.getStringExtra("rpc") ?: vm.state.value.rpcUrl
                val pair = intent.getIntExtra("pair", 0)
                vm.useDevAccounts(true, e2e)
                vm.connect(role, rpc, pair.takeIf { it in 1..2 })
            }
        }

        setContent {
            KlippelGoTheme {
                KlippelGoApp(vm)
            }
        }
    }

    // Off screen the app stops reading the chain, unless a driver is online or a ride is under way.
    override fun onStart() {
        super.onStart()
        vm.foreground(true)
    }

    override fun onStop() {
        vm.foreground(false)
        super.onStop()
    }
}

fun setAppLanguage(tag: String) {
    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
}
