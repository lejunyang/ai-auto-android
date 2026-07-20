package dev.aiauto.android

// 应用用途：承载 Android App 的 Compose 根界面与主导航入口。

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

import dev.aiauto.android.accessibility.settings.AccessibilitySettingsRepository
import dev.aiauto.android.bridge.DesktopBridgeController
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
        val accessibilityRepository = AccessibilitySettingsRepository.from(this)
        val bridgeController = DesktopBridgeController.from(this)

        setContent {
            AiAutoAndroidTheme {
                AiAutoAndroidApp(
                    providerRepository = providerRepository,
                    accessibilityRepository = accessibilityRepository,
                    bridgeController = bridgeController,
                )
            }
        }
    }
}
