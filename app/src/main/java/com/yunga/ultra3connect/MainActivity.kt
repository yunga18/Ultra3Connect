package com.yunga.ultra3connect

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID

private val Bg = Color(0xFF090D12)
private val Panel = Color(0xFF151D26)
private val Green = Color(0xFF8BE36A)
private val Muted = Color(0xFF9AA8B5)

class MainActivity : ComponentActivity() {
    private val devices = mutableStateListOf<BluetoothDevice>()
    private val deviceNames = mutableStateMapOf<String, String>()
    private val services = mutableStateListOf<BluetoothGattService>()
    private val logLines = mutableStateListOf<String>()
    private var gatt: BluetoothGatt? = null
    private var connected by mutableStateOf(false)
    private var scanning by mutableStateOf(false)
    private var selectedAddress by mutableStateOf<String?>(null)
    private var status by mutableStateOf("Listo para buscar dispositivos")
    private var battery by mutableStateOf("Sin lectura")
    private var characteristicValue by mutableStateOf("Selecciona una característica para leerla")

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) startScan() else status = "Faltan permisos Bluetooth"
        }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val address = device.address
            runOnUiThread {
                if (devices.none { it.address == address }) devices.add(device)
                deviceNames[address] = try {
                    device.name ?: result.scanRecord?.deviceName ?: "Dispositivo sin nombre"
                } catch (_: SecurityException) { "Dispositivo Bluetooth" }
            }
        }
        override fun onScanFailed(errorCode: Int) {
            runOnUiThread { scanning = false; status = "Error de escaneo: $errorCode"; addLog(status) }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, statusCode: Int, newState: Int) {
            runOnUiThread {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connected = true
                    status = "Conectado · descubriendo servicios"
                    addLog("Conexión establecida")
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    connected = false
                    services.clear()
                    status = "Desconectado"
                    addLog("Desconectado (estado $statusCode)")
                    g.close()
                    if (gatt === g) gatt = null
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, statusCode: Int) {
            runOnUiThread {
                if (statusCode == BluetoothGatt.GATT_SUCCESS) {
                    services.clear()
                    services.addAll(g.services)
                    status = "${services.size} servicios encontrados"
                    addLog(status)
                } else {
                    status = "No se pudieron descubrir servicios: $statusCode"
                }
            }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            statusCode: Int
        ) {
            runOnUiThread {
                characteristicValue = if (statusCode == BluetoothGatt.GATT_SUCCESS) value.toHex()
                    else "Error de lectura: $statusCode"
                addLog("Lectura ${characteristic.uuid}: $characteristicValue")
                if (characteristic.uuid.toString().equals("00002a19-0000-1000-8000-00805f9b34fb", true)
                    && value.isNotEmpty()) battery = "${value[0].toInt() and 0xFF}%"
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            runOnUiThread { addLog("Notificación ${characteristic.uuid}: ${value.toHex()}") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Bg, surface = Panel, primary = Green,
                    onPrimary = Color.Black, onBackground = Color.White,
                    onSurface = Color.White
                )
            ) {
                UltraApp(
                    status = status, scanning = scanning, connected = connected,
                    devices = devices.toList(),
                    nameFor = { deviceNames[it] ?: "Dispositivo Bluetooth" },
                    selectedAddress = selectedAddress,
                    services = services.toList(),
                    battery = battery,
                    logLines = logLines.toList(),
                    characteristicValue = characteristicValue,
                    onScan = { requestPermissionsAndScan() },
                    onConnect = { connectTo(it) },
                    onDisconnect = { disconnect() },
                    onRead = { service, characteristic -> readCharacteristic(service, characteristic) }
                )
            }
        }
    }

    private fun requestPermissionsAndScan() {
        val permissions = if (Build.VERSION.SDK_INT >= 31) arrayOf(
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT
        ) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = permissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray()) else startScan()
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter
        if (adapter == null || !adapter.isEnabled) {
            status = "Activa Bluetooth para continuar"
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) { status = "Escáner BLE no disponible"; return }
        devices.clear()
        deviceNames.clear()
        scanning = true
        status = "Buscando dispositivos BLE…"
        addLog("Inicio de escaneo BLE")
        scanner.startScan(scanCallback)
        window.decorView.postDelayed({
            try { scanner.stopScan(scanCallback) } catch (_: SecurityException) {}
            scanning = false
            if (status.startsWith("Buscando")) status = "${devices.size} dispositivos encontrados"
        }, 12000)
    }

    @SuppressLint("MissingPermission")
    private fun connectTo(device: BluetoothDevice) {
        try {
            gatt?.close()
            services.clear()
            selectedAddress = device.address
            status = "Conectando a ${device.address}…"
            addLog("Conectando a ${device.address}")
            gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            status = "Permiso Bluetooth denegado"
        }
    }

    @SuppressLint("MissingPermission")
    private fun disconnect() {
        try { gatt?.disconnect() } catch (_: SecurityException) {}
    }

    @SuppressLint("MissingPermission")
    private fun readCharacteristic(service: BluetoothGattService, characteristic: BluetoothGattCharacteristic) {
        val current = gatt ?: return
        try {
            val ok = current.readCharacteristic(characteristic)
            characteristicValue = if (ok) "Solicitando lectura…" else "No se pudo iniciar la lectura"
        } catch (_: SecurityException) {
            characteristicValue = "Permiso Bluetooth denegado"
        }
    }

    private fun addLog(message: String) {
        logLines.add(0, message)
        while (logLines.size > 80) logLines.removeAt(logLines.lastIndex)
    }

    override fun onDestroy() {
        try { gatt?.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}

@Composable
private fun UltraApp(
    status: String, scanning: Boolean, connected: Boolean,
    devices: List<BluetoothDevice>, nameFor: (String) -> String,
    selectedAddress: String?, services: List<BluetoothGattService>,
    battery: String, logLines: List<String>, characteristicValue: String,
    onScan: () -> Unit, onConnect: (BluetoothDevice) -> Unit,
    onDisconnect: () -> Unit,
    onRead: (BluetoothGattService, BluetoothGattCharacteristic) -> Unit
) {
    var tab by remember { mutableStateOf(0) }
    Scaffold(containerColor = Bg, bottomBar = {
        NavigationBar(containerColor = Panel) {
            NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                icon = { Icon(Icons.Default.Bluetooth, null) }, label = { Text("Dispositivo") })
            NavigationBarItem(selected = tab == 1, onClick = { tab = 1 },
                icon = { Icon(Icons.Default.BatteryFull, null) }, label = { Text("Servicios") })
            NavigationBarItem(selected = tab == 2, onClick = { tab = 2 },
                icon = { Icon(Icons.Default.Refresh, null) }, label = { Text("Registro") })
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().background(Bg).padding(padding).padding(18.dp)) {
            Text("ULTRA 3", color = Green, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Text("Connect", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            when (tab) {
                0 -> DeviceScreen(status, scanning, connected, devices, nameFor, selectedAddress,
                    battery, onScan, onConnect, onDisconnect)
                1 -> ServicesScreen(services, characteristicValue, onRead)
                else -> LogScreen(logLines)
            }
        }
    }
}

@Composable
private fun DeviceScreen(
    status: String, scanning: Boolean, connected: Boolean, devices: List<BluetoothDevice>,
    nameFor: (String) -> String, selectedAddress: String?, battery: String,
    onScan: () -> Unit, onConnect: (BluetoothDevice) -> Unit, onDisconnect: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Bluetooth, null, tint = Green, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (connected) "Reloj conectado" else "Sin conexión", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(status, color = Muted, fontSize = 12.sp)
                }
                Box(Modifier.size(9.dp).background(if (connected) Green else Muted, RoundedCornerShape(50)))
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Batería", color = Muted, fontSize = 12.sp)
                    Text(battery, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                Button(onClick = if (connected) onDisconnect else onScan,
                    colors = ButtonDefaults.buttonColors(containerColor = Green)) {
                    Text(if (connected) "Desconectar" else if (scanning) "Buscando…" else "Buscar reloj")
                }
            }
        }
    }
    Spacer(Modifier.height(18.dp))
    Text("DISPOSITIVOS CERCANOS", color = Muted, fontSize = 11.sp, letterSpacing = 1.5.sp)
    Spacer(Modifier.height(8.dp))
    if (devices.isEmpty()) {
        Text("Pulsa «Buscar reloj» para explorar dispositivos Bluetooth LE.", color = Muted, fontSize = 13.sp)
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(devices, key = { it.address }) { device ->
            val address = device.address
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onConnect(device) },
                colors = CardDefaults.cardColors(containerColor = Panel)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(nameFor(address), fontWeight = FontWeight.SemiBold)
                    Text(address, color = Muted, fontSize = 11.sp)
                    if (selectedAddress == address) Text("Seleccionado", color = Green, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun ServicesScreen(
    services: List<BluetoothGattService>, value: String,
    onRead: (BluetoothGattService, BluetoothGattCharacteristic) -> Unit
) {
    Text("SERVICIOS GATT", color = Muted, fontSize = 11.sp, letterSpacing = 1.5.sp)
    Spacer(Modifier.height(8.dp))
    Text(value, color = Green, fontSize = 12.sp)
    Spacer(Modifier.height(8.dp))
    if (services.isEmpty()) Text("Conecta el reloj para descubrir sus servicios.", color = Muted)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(services, key = { it.uuid.toString() }) { service ->
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("Servicio", color = Green, fontWeight = FontWeight.Bold)
                    Text(service.uuid.toString(), fontSize = 11.sp, color = Muted)
                    service.characteristics.forEach { characteristic ->
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(characteristic.uuid.toString(), fontSize = 11.sp)
                                Text(propertiesText(characteristic), color = Muted, fontSize = 10.sp)
                            }
                            TextButton(onClick = { onRead(service, characteristic) }) { Text("Leer") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogScreen(lines: List<String>) {
    Text("EVENTOS BLUETOOTH", color = Muted, fontSize = 11.sp, letterSpacing = 1.5.sp)
    Spacer(Modifier.height(8.dp))
    if (lines.isEmpty()) Text("Todavía no hay eventos.", color = Muted)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(lines) { line ->
            Text("›  $line", color = Color(0xFFCAD5DF), fontSize = 12.sp)
            HorizontalDivider(color = Panel)
        }
    }
}

private fun propertiesText(c: BluetoothGattCharacteristic): String {
    val p = c.properties
    val labels = mutableListOf<String>()
    if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) labels.add("READ")
    if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) labels.add("WRITE")
    if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) labels.add("WRITE_NO_RESPONSE")
    if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) labels.add("NOTIFY")
    if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) labels.add("INDICATE")
    return if (labels.isEmpty()) "Sin propiedades conocidas" else labels.joinToString(" · ")
}

private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }
