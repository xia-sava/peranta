package to.sava.peranta

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import to.sava.peranta.android.androidConfigRepository

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val sendEnabled = androidConfigRepository().load().sendEnabled

        setContent {
            SendRoleApp(sendEnabled)
        }
    }
}
