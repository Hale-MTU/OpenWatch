package tech.mmarca.openwatch

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.AndroidEntryPoint
import tech.mmarca.openwatch.features.healthconnect.HealthConnectSetupRoute
import tech.mmarca.openwatch.ui.theme.OpenWatchTheme

/** What Health Connect opens when it sends the user to set this app up. */
@AndroidEntryPoint
class HealthConnectOnboardingActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OpenWatchTheme(dynamicColor = true) {
                HealthConnectSetupRoute(
                    onContinue = {
                        startActivity(
                            Intent(this, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            },
                        )
                        finish()
                    },
                )
            }
        }
    }
}
