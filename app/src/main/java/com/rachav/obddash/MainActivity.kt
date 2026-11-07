package com.rachav.obddash

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.rachav.obddash.ui.ObdApp
import com.rachav.obddash.ui.ObdTheme
import com.rachav.obddash.ui.ObdViewModel

class MainActivity : ComponentActivity() {

    private val vm: ObdViewModel by viewModels()
    private var btGranted by mutableStateOf(false)

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            btGranted = granted
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        btGranted = hasBtPermission()
        if (!btGranted) requestBt()

        setContent {
            ObdTheme {
                ObdApp(
                    vm = vm,
                    btGranted = btGranted,
                    onRequestBt = ::requestBt,
                    bondedDevices = ::bondedDevices,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        btGranted = hasBtPermission()
    }

    // Antes de Android 12 basta con el permiso normal BLUETOOTH; desde 12 hay que pedir BLUETOOTH_CONNECT.
    private fun hasBtPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestBt() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun bondedDevices(): List<BluetoothDevice> {
        if (!hasBtPermission()) return emptyList()
        return BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.toList().orEmpty()
    }
}
