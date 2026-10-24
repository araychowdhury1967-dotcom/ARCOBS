package com.mobilestreaming.studio

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.mobilestreaming.studio.ui.StudioApp
import com.mobilestreaming.studio.ui.StudioTheme

class MainActivity : ComponentActivity() {
    private val viewModel: StudioViewModel by viewModels()
    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            viewModel.startScreenCapture(result.resultCode, result.data!!)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StudioTheme(viewModel = viewModel) {
                StudioApp(
                    viewModel = viewModel,
                    onRequestScreenCapture = ::requestScreenCapture,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) viewModel.startPreview()
    }

    override fun onStop() {
        viewModel.stopPreview()
        super.onStop()
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }
}
