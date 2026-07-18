package dev.aiauto.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

import dev.aiauto.android.provider.ProviderConfigRepository
import dev.aiauto.android.security.AndroidKeystoreSecretCipher
import dev.aiauto.android.ui.AiAutoAndroidApp
import dev.aiauto.android.ui.theme.AiAutoAndroidTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val providerRepository = ProviderConfigRepository(
            preferences = getSharedPreferences("provider-config", MODE_PRIVATE),
            secretCipher = AndroidKeystoreSecretCipher(),
        )

        setContent {
            AiAutoAndroidTheme {
                AiAutoAndroidApp(providerRepository = providerRepository)
            }
        }
    }
}
