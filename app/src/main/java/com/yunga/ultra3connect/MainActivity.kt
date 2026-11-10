package com.yunga.ultra3connect

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
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
import android.bluetooth.le.ScanSettings
import android.location.LocationManager
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
    private val signalStrengths = mutableStateMapOf<String, Int>()
    private var scanSession = 0
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
            if (result.isNotEmpty() && result.values.all { it }) {
                startScan()
            } else {
                status = "Permisos de dispositivos cercanos denegados"
                addLog(status)
            }
        }

    private val directPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) connectToAddress(pendingAddress)
            else {
                status = "Permiso Bluetooth Connect denegado"
                addLog(status)
            }
        }
    private var pendingAddress = ""

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val address = device.address
            runOnUiThread {
                val newDevice = devices.none { it.address == address }
                if (newDevice) devices.add(device)
                deviceNames[address] = try {
                    result.scanRecord?.deviceName ?: device.name ?: "Sin nombre"
                } catch (_: SecurityException) { "Dispositivo Bluetooth" }
                signalStrengths[address] = result.rssi
                if (newDevice) addLog("Detectado: " + deviceNames[address] + " · " + address + " · " + result.rssi + " dBm")
            }
        }
        override fun onScanFailed(errorCode: Int) {
            runOnUiThread {
                scanning = false
                scanSession++
                status = "Error de escaneo BLE: " + errorCode
                addLog(status)
            }
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
                    val batteryChar = g.getService(UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb"))
                        ?.getCharacteristic(UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb"))
                    if (batteryChar != null && batteryChar.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                        try {
                            @SuppressLint("MissingPermission")
                            g.readCharacteristic(batteryChar)
                        } catch (_: SecurityException) { addLog("Sin permiso para leer batería") }
                    }
                } else {
                    status = "No se pudieron descubrir servicios: $statusCode"
                    addLog(status)
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
                    rssiFor = { signalStrengths[it] },
                    onScan = { if (scanning) stopScan() else requestPermissionsAndScan() },
                    onDirectConnect = { requestDirectConnect(it) },
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
        if (scanning) stopScan()
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter
        if (adapter == null || !adapter.isEnabled) {
            status = "Activa Bluetooth para buscar el reloj"
            addLog(status)
            return
        }
        if (Build.VERSION.SDK_INT in 28..30) {
            val location = getSystemService(LOCATION_SERVICE) as LocationManager
            if (!location.isLocationEnabled) {
                status = "Activa Ubicación del teléfono para el escaneo BLE"
                addLog(status)
                return
            }
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            status = "Escáner BLE no disponible"
            addLog(status)
            return
        }
        devices.clear()
        deviceNames.clear()
        signalStrengths.clear()
        scanning = true
        scanSession++
        val session = scanSession
        status = "Escaneo intenso de 25 segundos..."
        addLog("Iniciando escaneo BLE sin filtros · modo LOW_LATENCY")
        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0L)
                .build()
            scanner.startScan(null, settings, scanCallback)
            window.decorView.postDelayed({
                if (scanning && scanSession == session) stopScan()
            }, 25000L)
        } catch (e: Exception) {
            scanning = false
            scanSession++
            status = "Error iniciando escaneo: " + (e.message ?: e.javaClass.simpleName)
            addLog(status)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanSession++
        scanning = false
        try {
            val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
            manager.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {}
        status = if (devices.isEmpty())
            "No se encontraron dispositivos; revisa Permisos y Registro"
        else "Se encontraron " + devices.size + " dispositivos BLE"
        addLog(status)
    }

    private fun requestDirectConnect(address: String) {
        val clean = address.trim().uppercase()
        if (!BluetoothAdapter.checkBluetoothAddress(clean)) {
            status = "Dirección BLE inválida. Utiliza AA:BB:CC:DD:EE:FF"
            addLog(status)
            return
        }
        pendingAddress = clean
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            directPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else connectToAddress(clean)
    }

    @SuppressLint("MissingPermission")
    private fun connectToAddress(address: String) {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            status = "Activa Bluetooth primero"
            return
        }
        try {
            addLog("Conexión manual por dirección: " + address)
            connectTo(adapter.getRemoteDevice(address))
        } catch (e: Exception) {
            status = "Conexión manual falló: " + (e.message ?: e.javaClass.simpleName)
            addLog(status)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectTo(device: BluetoothDevice) {
        try {
            stopScan()
            gatt?.disconnect()
            gatt?.close()
            gatt = null
            connected = false
            services.clear()
            battery = "Sin lectura"
            selectedAddress = device.address
            status = "Conectando a " + device.address
            addLog(status)
            val target = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            gatt = target
            window.decorView.postDelayed({
                if (!connected && gatt === target && selectedAddress == device.address) {
                    status = "No respondió en 18 s; verifica que el reloj esté disponible"
                    addLog(status)
                    try { target?.disconnect(); target?.close() } catch (_: Exception) {}
                    if (gatt === target) gatt = null
                }
            }, 18000L)
        } catch (e: Exception) {
            status = "Error de conexión: " + (e.message ?: e.javaClass.simpleName)
            addLog(status)
        }
    }

    @SuppressLint("MissingPermission")
    private fun disconnect() {
        try { gatt?.disconnect() } catch (_: SecurityException) {}
    }

    @SuppressLint("MissingPermission")
    private fun readCharacteristic(service: BluetoothGattService, characteristic: BluetoothGattCharacteristic) {
        val current = gatt ?: return
        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) {
            characteristicValue = "Esta característica no permite READ"
            addLog(characteristicValue)
            return
        }
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
        stopScan()
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
    rssiFor: (String) -> Int?,
    onScan: () -> Unit, onDirectConnect: (String) -> Unit, onConnect: (BluetoothDevice) -> Unit,
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
                    battery, rssiFor, onScan, onDirectConnect, onConnect, onDisconnect)
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
    rssiFor: (String) -> Int?,
    onScan: () -> Unit, onDirectConnect: (String) -> Unit,
    onConnect: (BluetoothDevice) -> Unit, onDisconnect: () -> Unit
) {
    var manualAddress by remember { mutableStateOf("") }
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
                    Text(if (connected) "Desconectar" else if (scanning) "Detener" else "Buscar reloj")
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text("Si no aparece Ultra 3, ingresa la dirección LE que muestra el reloj.", color = Muted, fontSize = 12.sp)
    OutlinedTextField(
        value = manualAddress,
        onValueChange = { manualAddress = it },
        label = { Text("Dirección LE del reloj") },
        placeholder = { Text("AA:BB:CC:DD:EE:FF") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    TextButton(onClick = { onDirectConnect(manualAddress) }, enabled = manualAddress.isNotBlank()) {
        Text("Intentar conexión directa")
    }
    Spacer(Modifier.height(8.dp))
    Text("DISPOSITIVOS CERCANOS", color = Muted, fontSize = 11.sp, letterSpacing = 1.5.sp)
    Spacer(Modifier.height(8.dp))
    if (devices.isEmpty()) {
        Text("Pulsa «Buscar reloj» para explorar dispositivos Bluetooth LE.", color = Muted, fontSize = 13.sp)
    }
    val orderedDevices = devices.sortedWith(
        compareByDescending<BluetoothDevice> { nameFor(it.address).contains("ultra", ignoreCase = true) }
            .thenByDescending { rssiFor(it.address) ?: -127 }
    )
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(orderedDevices, key = { it.address }) { device ->
            val address = device.address
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onConnect(device) },
                colors = CardDefaults.cardColors(containerColor = Panel)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(nameFor(address), fontWeight = FontWeight.SemiBold)
                    Text(address + " · " + (rssiFor(address)?.toString() ?: "?") + " dBm", color = Muted, fontSize = 11.sp)
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
