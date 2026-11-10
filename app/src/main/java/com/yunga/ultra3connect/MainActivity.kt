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
import android.bluetooth.BluetoothStatusCodes
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

private val UART_SERVICE = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9f")
private val UART_RX = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9f")
private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

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
    private var uartState by mutableStateOf("Sin activar")
    private var uartSubscribed by mutableStateOf(false)
    private var uartPendingEnabled: Boolean? = null
    private var uartCount by mutableIntStateOf(0)
    private val uartMessages = mutableStateListOf<String>()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val denied = result.filterValues { !it }.keys
            if (result.isNotEmpty() && denied.isEmpty()) {
                addLog("Permisos BLE y ubicación concedidos")
                startScan()
            } else {
                status = "Permisos pendientes: " + denied.joinToString { it.substringAfterLast('.') }
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
                if (gatt !== g) {
                    addLog("Evento antiguo descartado: GATT=$statusCode, state=$newState")
                    try { g.close() } catch (_: Exception) {}
                    return@runOnUiThread
                }
                addLog("GATT callback: status=$statusCode, newState=$newState")
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        connected = true
                        status = "BLE conectado · status=$statusCode · descubriendo servicios"
                        addLog("Conexión GATT abierta, solicitando servicios")
                        try {
                            val sent = g.discoverServices()
                            addLog("discoverServices enviado=$sent")
                            if (!sent) status = "Fallo solicitando servicios GATT"
                        } catch (e: Exception) {
                            status = "Error descubrimiento GATT: " + (e.message ?: e.javaClass.simpleName)
                            addLog(status)
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        connected = false
                        services.clear()
                        uartSubscribed = false
                        uartPendingEnabled = null
                        uartState = "Desconectado"
                        status = "BLE desconectado · GATT status=$statusCode"
                        addLog("Desconectado: status=$statusCode (conserva este dato para diagnóstico)")
                        try { g.close() } catch (_: Exception) {}
                        if (gatt === g) gatt = null
                    }
                    else -> addLog("Estado GATT intermedio: " + newState)
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
            runOnUiThread { recordNotification(g, characteristic, value) }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val snapshot = characteristic.value?.copyOf() ?: byteArrayOf()
            runOnUiThread { recordNotification(g, characteristic, snapshot) }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            statusCode: Int
        ) {
            runOnUiThread {
                if (g !== gatt || descriptor.uuid != CCCD_UUID ||
                    descriptor.characteristic.uuid != UART_RX) return@runOnUiThread
                val enabling = uartPendingEnabled ?: return@runOnUiThread
                uartPendingEnabled = null
                if (statusCode == BluetoothGatt.GATT_SUCCESS) {
                    uartSubscribed = enabling
                    uartState = if (enabling) "Escuchando notificaciones UART" else "Recepción detenida"
                } else {
                    uartState = "Error CCCD: GATT $statusCode"
                    if (enabling) {
                        uartSubscribed = false
                        try { g.setCharacteristicNotification(descriptor.characteristic, false) }
                        catch (_: Exception) {}
                    }
                }
                addLog("Configuración UART: " + uartState)
            }
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
                    uartState = uartState,
                    uartSubscribed = uartSubscribed,
                    uartBusy = uartPendingEnabled != null,
                    uartCount = uartCount,
                    uartMessages = uartMessages.toList(),
                    rssiFor = { signalStrengths[it] },
                    onToggleUart = { toggleUartSubscription() },
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
        // Full BLE scan: request location rather than using neverForLocation,
        // which can filter some BLE advertisements.
        val permissions = if (Build.VERSION.SDK_INT >= 31) arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = permissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            addLog("Solicitando: " + missing.joinToString { it.substringAfterLast('.') })
            permissionLauncher.launch(missing.toTypedArray())
        } else startScan()
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
        if (Build.VERSION.SDK_INT >= 28) {
            val location = getSystemService(LOCATION_SERVICE) as LocationManager
            if (!location.isLocationEnabled) {
                status = "Activa Ubicación del teléfono para encontrar el reloj BLE"
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
            uartSubscribed = false
            uartPendingEnabled = null
            uartCount = 0
            uartMessages.clear()
            uartState = "Sin activar"
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
        addLog("Desconexión solicitada por el usuario")
        try { gatt?.disconnect() } catch (_: SecurityException) {
            addLog("Permiso denegado al desconectar")
        }
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

    private fun recordNotification(
        sourceGatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        if (sourceGatt !== gatt) return
        val hex = value.toHex()
        addLog("Notificación ${characteristic.uuid}: $hex")
        if (characteristic.uuid == UART_RX && characteristic.service.uuid == UART_SERVICE) {
            uartCount++
            val ascii = value.joinToString("") { b ->
                val ch = b.toInt() and 0xFF
                if (ch in 32..126) ch.toChar().toString() else "."
            }
            uartMessages.add(0, "HEX: $hex" + if (ascii.isNotEmpty()) "\nASCII: $ascii" else "")
            while (uartMessages.size > 40) uartMessages.removeAt(uartMessages.lastIndex)
        }
    }

    @SuppressLint("MissingPermission")
    private fun toggleUartSubscription() {
        val current = gatt
        if (!connected || current == null) {
            uartState = "Conecta primero el reloj"
            return
        }
        if (uartPendingEnabled != null) return
        val target = current.getService(UART_SERVICE)?.getCharacteristic(UART_RX)
        if (target == null) {
            uartState = "Servicio UART o característica RX no disponibles"
            addLog(uartState)
            return
        }
        if (target.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0) {
            uartState = "La característica RX no admite NOTIFY"
            addLog(uartState)
            return
        }
        val descriptor = target.getDescriptor(CCCD_UUID)
        if (descriptor == null) {
            uartState = "No existe descriptor CCCD 0x2902"
            addLog(uartState)
            return
        }
        val enable = !uartSubscribed
        try {
            if (!current.setCharacteristicNotification(target, enable)) {
                uartState = "No se pudo configurar la recepción local"
                addLog(uartState)
                return
            }
            val bytes = if (enable) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            uartPendingEnabled = enable
            uartState = if (enable) "Activando notificaciones..." else "Deteniendo recepción..."
            val started = if (Build.VERSION.SDK_INT >= 33) {
                current.writeDescriptor(descriptor, bytes) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = bytes
                @Suppress("DEPRECATION")
                current.writeDescriptor(descriptor)
            }
            if (!started) {
                uartPendingEnabled = null
                uartState = "No se pudo iniciar la escritura del CCCD"
                if (enable) current.setCharacteristicNotification(target, false)
                addLog(uartState)
            } else {
                addLog("Enviando configuración CCCD UART: " + (if (enable) "activar" else "desactivar"))
            }
        } catch (e: Exception) {
            uartPendingEnabled = null
            uartState = "Error configurando UART: " + (e.message ?: e.javaClass.simpleName)
            addLog(uartState)
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
    uartState: String, uartSubscribed: Boolean, uartBusy: Boolean,
    uartCount: Int, uartMessages: List<String>,
    rssiFor: (String) -> Int?,
    onToggleUart: () -> Unit,
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
                1 -> ServicesScreen(
                    services, characteristicValue, connected, uartState, uartSubscribed,
                    uartBusy, uartCount, uartMessages, onToggleUart, onRead
                )
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
    services: List<BluetoothGattService>, value: String, connected: Boolean,
    uartState: String, uartSubscribed: Boolean, uartBusy: Boolean,
    uartCount: Int, uartMessages: List<String>, onToggleUart: () -> Unit,
    onRead: (BluetoothGattService, BluetoothGattCharacteristic) -> Unit
) {
    Text("RECEPCIÓN UART", color = Muted, fontSize = 11.sp, letterSpacing = 1.5.sp)
    Spacer(Modifier.height(8.dp))
    Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(uartState, color = if (uartSubscribed) Green else Muted, fontWeight = FontWeight.SemiBold)
            Text("Paquetes recibidos: $uartCount", fontSize = 13.sp, color = Muted)
            Spacer(Modifier.height(8.dp))
            Button(
                enabled = connected && !uartBusy && services.any { it.uuid == UART_SERVICE },
                onClick = onToggleUart
            ) {
                Text(if (uartSubscribed) "Detener recepción" else "Activar recepción UART")
            }
            Text(
                "Escucha solamente datos enviados por el reloj. No envía órdenes ni usa OTA.",
                fontSize = 11.sp, color = Muted
            )
            if (uartMessages.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                uartMessages.take(5).forEach { message ->
                    Text(message, fontSize = 11.sp, color = Green)
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
    Spacer(Modifier.height(14.dp))
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
                            if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                                TextButton(onClick = { onRead(service, characteristic) }) { Text("Leer") }
                            }
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
