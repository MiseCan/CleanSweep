package com.cleansweep.ui

import android.app.Activity
import android.content.Context
import android.content.IntentSender
import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.cleansweep.R
import com.cleansweep.diagnostics.ErrorJournal
import com.cleansweep.ui.navigation.AppNavigation
import com.cleansweep.ui.theme.CleanSweepTheme
import java.io.IOException
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        val chinese = Configuration().apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
        super.attachBaseContext(newBase.createConfigurationContext(chinese))
    }

    private val model: PhotoViewModel by viewModels()
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { model.refresh() }
    private val removalLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result -> model.removalAnswered(result.resultCode == Activity.RESULT_OK) }

    private val exportLogLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) lifecycleScope.launch {
            val message = try {
                withContext(Dispatchers.IO) {
                    val output = contentResolver.openOutputStream(uri, "wt")
                        ?: throw IOException("Unable to open export file")
                    output.use(ErrorJournal::export)
                }
                R.string.export_error_log_done
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                ErrorJournal.warn("Could not export diagnostics", error)
                R.string.export_error_log_failed
            }
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            state.permissionRequest?.let { request ->
                LaunchedEffect(request) {
                    try {
                        removalLauncher.launch(request)
                    } catch (error: IntentSender.SendIntentException) {
                        ErrorJournal.warn("Could not launch the photo removal request", error)
                        model.removalAnswered(false)
                    } catch (error: IllegalStateException) {
                        ErrorJournal.warn("Removal request launcher unavailable", error)
                        model.removalAnswered(false)
                    }
                    model.requestLaunched()
                }
            }
            CleanSweepTheme {
                AppNavigation(state, model, onExportErrorLog = {
                    exportLogLauncher.launch("CleanSweep-errors-${LocalDate.now()}.txt")
                }) { permissionLauncher.launch(model.permissions) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model.refresh()
    }
}
