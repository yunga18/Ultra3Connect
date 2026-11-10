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
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Notifications
import androidx.core.app.NotificationManagerCompat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID
import java.util.Date
import java.util.Locale
import java.text.SimpleDateFormat

private val Bg = Color(0xFF090D12)
private val Panel = Color(0xFF151D26)
private val Green = Color(0xFF8BE36A)
private val Muted = Color(0xFF9AA8B5)

private val UART_SERVICE = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9f")
private val UART_RX = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9f")
private val UART_WRITE = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9f")
private val EXTRA_SERVICE = UUID.fromString("0000ff12-0000-1000-8000-00805f9b34fb")
private val EXTRA_NOTIFY = UUID.fromString("0000ff14-0000-1000-8000-00805f9b34fb")
private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

class MainActivity : ComponentActivity() {
    private val devices = mutableStateListOf<BluetoothDevice>()
    private val deviceNames = mutableStateMapOf<String, String>()
    private val signalStrengths = mutableStateMapOf<String, Int>()
    private var scanSession = 0
    private val services = mutableStateListOf<BluetoothGattService>()
    private val logLines = mutableStateListOf<String>()
    private val savedMeasurements = mutableStateListOf<StoredMeasurement>()
    private lateinit var watchNotificationSettings: WatchNotificationSettings
    private var notificationAccessGranted by mutableStateOf(false)
    private var notificationForwardEnabled by mutableStateOf(false)
    private val notificationAppAllowed = mutableStateMapOf<String, Boolean>()
    private var notificationForwardedCount by mutableIntStateOf(0)
    private var notificationDroppedCount by mutableIntStateOf(0)
    private var notificationDetectedCount by mutableIntStateOf(0)
    private var notificationListenerStatus by mutableStateOf("Sin eventos recibidos desde Android")
    private var remoteDisconnectCount by mutableIntStateOf(0)
    private var reconnectAttempt by mutableIntStateOf(0)
    private var intentionalDisconnect = false
    private var retryGeneration = 0
    private val notificationQueue = ArrayDeque<IncomingWatchNotice>()
    private val maximumPendingNotifications = 8
    private lateinit var measurementStore: LocalMeasurementStore
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
    private val dfAssembler = DfFrameAssembler()
    private var dfFrameCount by mutableIntStateOf(0)
    private var dfVerifiedCount by mutableIntStateOf(0)
    private val dfFrameMessages = mutableStateListOf<String>()
    private var heartCandidate by mutableStateOf("Sin datos")
    private var pressureCandidate by mutableStateOf("Sin datos")
    private var measurementRequestStatus by mutableStateOf("Activa la recepción UART antes de iniciar una medición")
    private var pendingMeasurement by mutableStateOf<ExperimentalVitalDecoder.Kind?>(null)
    private var measurementRequestToken = 0
    private var notifyTestBusy by mutableStateOf(false)
    private var notifyTestStatus by mutableStateOf("Prueba manual sin acceso a tus mensajes")
    private var notifyTestToken = 0
    private var lastCounterCandidate: Int? = null
    private var lastCounterTimestampMs: Long? = null
    private var extraState by mutableStateOf("Sin activar")
    private var extraSubscribed by mutableStateOf(false)
    private var extraPendingEnabled: Boolean? = null
    private var extraCount by mutableIntStateOf(0)
    private val extraMessages = mutableStateListOf<String>()

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
                        val connection = g
                        window.decorView.postDelayed({
                            if (gatt === connection && connected) {
                                reconnectAttempt = 0
                            }
                        }, 45000L)
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
                        notifyTestToken++
                        notifyTestBusy = false
                        notificationQueue.clear()
                        if (statusCode == 19) {
                            remoteDisconnectCount++
                            addLog("GATT 19: reloj cerró el enlace BLE; causa exacta no determinada")
                        }
                        connected = false
                        services.clear()
                        uartSubscribed = false
                        uartPendingEnabled = null
                        uartState = "Desconectado"
                        pendingMeasurement = null
                        measurementRequestToken++
                        measurementRequestStatus = "Sin conexión con el reloj"
                        notifyTestToken++
                        notifyTestBusy = false
                        notifyTestStatus = "Desconectado"
                        extraSubscribed = false
                        extraPendingEnabled = null
                        extraState = "Desconectado"
                        status = "BLE desconectado · GATT status=$statusCode"
                        addLog("Desconectado: status=$statusCode (conserva este dato para diagnóstico)")
                        try { g.close() } catch (_: Exception) {}
                        if (gatt === g) gatt = null
                        if (statusCode == 19 && !intentionalDisconnect &&
                            reconnectAttempt < 3 && selectedAddress != null) {
                            val address = selectedAddress!!
                            reconnectAttempt++
                            val attempt = reconnectAttempt
                            val generation = retryGeneration
                            val delayMs = (attempt * 3500L)
                            status = "Reconexión controlada $attempt/3 en unos segundos"
                            addLog("Reconexión programada: intento $attempt/3 tras GATT 19")
                            window.decorView.postDelayed({
                                if (generation == retryGeneration && !intentionalDisconnect &&
                                    !connected && gatt == null) {
                                    try {
                                        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
                                        val device = manager.adapter?.getRemoteDevice(address)
                                        if (device != null) connectTo(device, automaticRetry = true)
                                    } catch (e: Exception) {
                                        addLog("Reconexión no iniciada: " +
                                            (e.message ?: e.javaClass.simpleName))
                                    }
                                }
                            }, delayMs)
                        }
                    }
                    else -> addLog("Estado GATT intermedio: " + newState)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, statusCode: Int) {
            runOnUiThread {
                if (g !== gatt) return@runOnUiThread
                if (statusCode == BluetoothGatt.GATT_SUCCESS) {
                    services.clear()
                    services.addAll(g.services)
                    status = "${services.size} servicios encontrados"
                    addLog(status)
                    // The earlier builds waited for a manual tap. Some watch
                    // sessions ended remotely before that happened. Subscribe
                    // as soon as the services are ready.
                    if (g.getService(UART_SERVICE)?.getCharacteristic(UART_RX) != null) {
                        addLog("Activando UART automáticamente tras descubrir servicios")
                        toggleUartSubscription()
                    } else {
                        addLog("No se encontró UART; leyendo batería solamente")
                        readBatteryAfterInitialization(g)
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
                if (g !== gatt) return@runOnUiThread
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

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            statusCode: Int
        ) {
            runOnUiThread {
                if (g !== gatt || characteristic.uuid != UART_WRITE ||
                    characteristic.service.uuid != UART_SERVICE) return@runOnUiThread
                if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                    pendingMeasurement = null
                    measurementRequestToken++
                    measurementRequestStatus = "Error enviando solicitud: GATT $statusCode"
                    addLog(measurementRequestStatus)
                } else addLog("Confirmación local de escritura UART: GATT $statusCode")
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            statusCode: Int
        ) {
            runOnUiThread {
                if (g !== gatt || descriptor.uuid != CCCD_UUID) return@runOnUiThread
                val ch = descriptor.characteristic
                if (ch.uuid == UART_RX && ch.service.uuid == UART_SERVICE) {
                    val enabling = uartPendingEnabled ?: return@runOnUiThread
                    uartPendingEnabled = null
                    if (statusCode == BluetoothGatt.GATT_SUCCESS) {
                        uartSubscribed = enabling
                        uartState = if (enabling) "Escuchando notificaciones UART" else "Recepción detenida"
                    } else {
                        uartState = "Error CCCD: GATT $statusCode"
                        if (enabling) {
                            uartSubscribed = false
                            try { g.setCharacteristicNotification(ch, false) } catch (_: Exception) {}
                        }
                    }
                    addLog("Configuración UART: " + uartState)
                    // Read the battery only after the CCCD write finishes,
                    // avoiding concurrent GATT transactions.
                    readBatteryAfterInitialization(g)
                } else if (ch.uuid == EXTRA_NOTIFY && ch.service.uuid == EXTRA_SERVICE) {
                    val enabling = extraPendingEnabled ?: return@runOnUiThread
                    extraPendingEnabled = null
                    if (statusCode == BluetoothGatt.GATT_SUCCESS) {
                        extraSubscribed = enabling
                        extraState = if (enabling) "Escuchando notificaciones FF14" else "Recepción FF14 detenida"
                    } else {
                        extraState = "Error FF14 CCCD: GATT $statusCode"
                        if (enabling) {
                            extraSubscribed = false
                            try { g.setCharacteristicNotification(ch, false) } catch (_: Exception) {}
                        }
                    }
                    addLog("Configuración FF14: " + extraState)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        measurementStore = LocalMeasurementStore(this)
        savedMeasurements.addAll(measurementStore.load())
        watchNotificationSettings = WatchNotificationSettings(this)
        notificationForwardEnabled = watchNotificationSettings.enabled()
        WatchNotificationSources.supported.forEach {
            notificationAppAllowed[it.key] = watchNotificationSettings.allowed(it.key)
        }
        WatchNotificationBridge.receiver = { notice ->
            runOnUiThread { handleIncomingWatchNotice(notice) }
        }
        WatchNotificationBridge.diagnostic = { reason ->
            runOnUiThread {
                notificationListenerStatus = reason
                if (reason.startsWith("Android detectó")) notificationDetectedCount++
                addLog("Avisos Android: " + reason)
            }
        }
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
                    notificationAccessGranted = notificationAccessGranted,
                    notificationForwardEnabled = notificationForwardEnabled,
                    notificationAppAllowed = notificationAppAllowed.toMap(),
                    notificationForwardedCount = notificationForwardedCount,
                    notificationDroppedCount = notificationDroppedCount,
                    notificationDetectedCount = notificationDetectedCount,
                    notificationListenerStatus = notificationListenerStatus,
                    remoteDisconnectCount = remoteDisconnectCount,
                    reconnectAttempt = reconnectAttempt,
                    onOpenNotificationAccess = {
                        try {
                            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        } catch (e: Exception) {
                            addLog("No se pudo abrir ajustes de notificaciones: " +
                                (e.message ?: e.javaClass.simpleName))
                        }
                    },
                    onNotificationMasterChange = { enable ->
                        notificationForwardEnabled = enable && notificationAccessGranted
                        watchNotificationSettings.setEnabled(notificationForwardEnabled)
                        addLog("Reenvío de notificaciones: " +
                            if (notificationForwardEnabled) "activado" else "desactivado")
                    },
                    onNotificationAppChange = { key, enable ->
                        notificationAppAllowed[key] = enable
                        watchNotificationSettings.setAllowed(key, enable)
                        addLog("Permiso local de " + key + ": " + if (enable) "sí" else "no")
                    },
                    savedMeasurements = savedMeasurements.toList(),
                    onClearMeasurements = {
                        measurementStore.clear()
                        savedMeasurements.clear()
                        heartCandidate = "Sin datos"
                        pressureCandidate = "Sin datos"
                        addLog("Historial local de mediciones eliminado")
                    },
                    characteristicValue = characteristicValue,
                    uartState = uartState,
                    uartSubscribed = uartSubscribed,
                    uartBusy = uartPendingEnabled != null,
                    uartCount = uartCount,
                    uartMessages = uartMessages.toList(),
                    dfFrameCount = dfFrameCount,
                    dfVerifiedCount = dfVerifiedCount,
                    dfFrameMessages = dfFrameMessages.toList(),
                    heartCandidate = heartCandidate,
                    pressureCandidate = pressureCandidate,
                    measurementRequestStatus = measurementRequestStatus,
                    measurementInProgress = pendingMeasurement != null,
                    onRequestMeasurement = { requestWatchMeasurement(it) },
                    notifyTestStatus = notifyTestStatus,
                    notifyTestBusy = notifyTestBusy,
                    onNotifyTest = { sendNotificationTest() },
                    extraState = extraState,
                    extraSubscribed = extraSubscribed,
                    extraBusy = extraPendingEnabled != null,
                    extraCount = extraCount,
                    extraMessages = extraMessages.toList(),
                    rssiFor = { signalStrengths[it] },
                    onToggleUart = { toggleUartSubscription() },
                    onMarkEvent = { markActivityEvent(it) },
                    onToggleExtra = { toggleExtraSubscription() },
                    onScan = { if (scanning) stopScan() else requestPermissionsAndScan() },
                    onDirectConnect = { requestDirectConnect(it) },
                    onConnect = { connectTo(it) },
                    onDisconnect = { disconnect() },
                    onRead = { service, characteristic -> readCharacteristic(service, characteristic) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        notificationAccessGranted = NotificationManagerCompat
            .getEnabledListenerPackages(this).contains(packageName)
        if (!notificationAccessGranted && notificationForwardEnabled) {
            notificationForwardEnabled = false
            watchNotificationSettings.setEnabled(false)
            notificationListenerStatus = "Permiso Android no disponible. Reenvío desactivado."
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
    private fun connectTo(device: BluetoothDevice, automaticRetry: Boolean = false) {
        if (!automaticRetry) reconnectAttempt = 0
        intentionalDisconnect = false
        retryGeneration++
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
            dfAssembler.reset()
            dfFrameCount = 0
            dfVerifiedCount = 0
            dfFrameMessages.clear()
            heartCandidate = "Sin datos"
            pressureCandidate = "Sin datos"
            pendingMeasurement = null
            measurementRequestToken++
            measurementRequestStatus = "Activa la recepción UART antes de iniciar una medición"
            notifyTestToken++
            notifyTestBusy = false
            notificationQueue.clear()
            notifyTestStatus = "Prueba manual sin acceso a tus mensajes"
            lastCounterCandidate = null
            lastCounterTimestampMs = null
            uartState = "Sin activar"
            extraSubscribed = false
            extraPendingEnabled = null
            extraCount = 0
            extraMessages.clear()
            extraState = "Sin activar"
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
        intentionalDisconnect = true
        retryGeneration++
        reconnectAttempt = 0
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
        if (characteristic.uuid == EXTRA_NOTIFY && characteristic.service.uuid == EXTRA_SERVICE) {
            extraCount++
            extraMessages.add(0, "HEX: $hex")
            while (extraMessages.size > 40) extraMessages.removeAt(extraMessages.lastIndex)
        }
        if (characteristic.uuid == UART_RX && characteristic.service.uuid == UART_SERVICE) {
            uartCount++
            val waitingFor = pendingMeasurement
            if (waitingFor != null && WatchControlProtocol.isMeasurementAcknowledgment(value, waitingFor)) {
                measurementRequestStatus = "El reloj respondió a la solicitud; esperando el resultado..."
                addLog("ACK de solicitud " + measurementKindLabel(waitingFor) + " recibido")
            }
            val completedFrames = dfAssembler.append(value)
            for (frame in completedFrames) {
                dfFrameCount++
                if (frame.isValid) dfVerifiedCount++
                val result = if (frame.isValid) "OK" else "INVALID"
                val possibleKind = when {
                    frame.command == 0x05 && frame.subcommand == 0x04 -> "hipótesis: pulso"
                    frame.command == 0x05 && frame.subcommand == 0x05 -> "hipótesis: presión arterial"
                    frame.command == 0x05 && frame.subcommand == 0x02 -> "hipótesis: pasos"
                    frame.command == 0x05 && frame.subcommand == 0x0E -> "hipótesis: SpO2"
                    else -> "sin clasificar"
                }
                val counterCandidate = frame.counterCandidate16Be
                val timeNow = SystemClock.elapsedRealtime()
                val counterNote = if (frame.isValid && counterCandidate != null) {
                    val previousCounter = lastCounterCandidate
                    val previousTimestamp = lastCounterTimestampMs
                    val deltaNote = if (previousCounter != null && previousTimestamp != null) {
                        val deltaCounter = (counterCandidate - previousCounter + 65536) % 65536
                        val deltaSeconds = (timeNow - previousTimestamp) / 1000.0
                        " · delta16=$deltaCounter vs dt=%.1fs".format(Locale.US, deltaSeconds)
                    } else ""
                    lastCounterCandidate = counterCandidate
                    lastCounterTimestampMs = timeNow
                    " · campo15-16=%04X".format(Locale.US, counterCandidate) + deltaNote
                } else ""
                val experimentalReading = ExperimentalVitalDecoder.decode(frame)
                if (experimentalReading != null) {
                    when (experimentalReading.kind) {
                        ExperimentalVitalDecoder.Kind.HEART_RATE ->
                            heartCandidate = experimentalReading.valueLabel
                        ExperimentalVitalDecoder.Kind.BLOOD_PRESSURE ->
                            pressureCandidate = experimentalReading.valueLabel
                    }
                    val entry = StoredMeasurement(
                        kind = experimentalReading.kind,
                        valueLabel = experimentalReading.valueLabel,
                        receivedAtMillis = System.currentTimeMillis(),
                        packetHex = frame.bytes.toHex()
                    )
                    savedMeasurements.add(0, entry)
                    while (savedMeasurements.size > 100) savedMeasurements.removeAt(savedMeasurements.lastIndex)
                    measurementStore.save(savedMeasurements)
                    if (pendingMeasurement == experimentalReading.kind) {
                        pendingMeasurement = null
                        measurementRequestToken++
                        measurementRequestStatus = "Recibido desde el reloj: " + experimentalReading.valueLabel
                        addLog("Medición solicitada completada: " + experimentalReading.valueLabel)
                    }
                }
                val label = "CMD=%02X SUB=%02X payload=%d checksum=%s [%s]%s".format(
                    Locale.US, frame.command, frame.subcommand,
                    frame.actualPayloadLength, result, possibleKind, counterNote
                ) + (experimentalReading?.let { " · candidato=${it.valueLabel}" } ?: "")
                val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
                dfFrameMessages.add(0, timestamp + " · " + label + "\n" + frame.bytes.toHex())
                while (dfFrameMessages.size > 40) dfFrameMessages.removeAt(dfFrameMessages.lastIndex)
                addLog("Trama DF reconstruida: " + label + " · " + frame.bytes.toHex())
            }
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
        if (uartPendingEnabled != null || extraPendingEnabled != null) return
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

    @SuppressLint("MissingPermission")
    private fun toggleExtraSubscription() {
        val current = gatt
        if (!connected || current == null) {
            extraState = "Conecta primero el reloj"
            return
        }
        // GATT permits only one descriptor write operation at a time.
        if (uartPendingEnabled != null || extraPendingEnabled != null) return
        val target = current.getService(EXTRA_SERVICE)?.getCharacteristic(EXTRA_NOTIFY)
        if (target == null) {
            extraState = "Canal FF14 no encontrado"
            addLog(extraState)
            return
        }
        if (target.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0) {
            extraState = "FF14 no admite NOTIFY"
            addLog(extraState)
            return
        }
        val descriptor = target.getDescriptor(CCCD_UUID)
        if (descriptor == null) {
            extraState = "FF14 no contiene CCCD 0x2902"
            addLog(extraState)
            return
        }
        val enable = !extraSubscribed
        try {
            if (!current.setCharacteristicNotification(target, enable)) {
                extraState = "No se pudo activar la recepción FF14 local"
                addLog(extraState)
                return
            }
            val bytes = if (enable) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            extraPendingEnabled = enable
            extraState = if (enable) "Activando FF14..." else "Deteniendo FF14..."
            val started = if (Build.VERSION.SDK_INT >= 33) {
                current.writeDescriptor(descriptor, bytes) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = bytes
                @Suppress("DEPRECATION")
                current.writeDescriptor(descriptor)
            }
            if (!started) {
                extraPendingEnabled = null
                extraState = "No se pudo iniciar escritura del descriptor FF14"
                if (enable) current.setCharacteristicNotification(target, false)
                addLog(extraState)
            } else addLog("Configuración CCCD FF14 solicitada")
        } catch (e: Exception) {
            extraPendingEnabled = null
            extraState = "Error FF14: " + (e.message ?: e.javaClass.simpleName)
            addLog(extraState)
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestWatchMeasurement(kind: ExperimentalVitalDecoder.Kind) {
        val current = gatt
        if (current == null || !connected || !uartSubscribed) {
            measurementRequestStatus = "Conecta el reloj y activa primero la recepción UART"
            return
        }
        if (pendingMeasurement != null || uartPendingEnabled != null || extraPendingEnabled != null || notifyTestBusy) {
            measurementRequestStatus = "Ya hay una medición u operación Bluetooth en curso"
            return
        }
        val characteristic = current.getService(UART_SERVICE)?.getCharacteristic(UART_WRITE)
        if (characteristic == null || characteristic.properties and
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE == 0) {
            measurementRequestStatus = "El reloj no ofrece UART Write Without Response"
            addLog(measurementRequestStatus)
            return
        }
        val command = WatchControlProtocol.startMeasurementCommand(kind)
        try {
            val started = if (Build.VERSION.SDK_INT >= 33) {
                current.writeCharacteristic(
                    characteristic, command, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                characteristic.value = command
                @Suppress("DEPRECATION")
                current.writeCharacteristic(characteristic)
            }
            if (!started) {
                measurementRequestStatus = "No se pudo enviar la solicitud al reloj"
                addLog(measurementRequestStatus)
                return
            }
            pendingMeasurement = kind
            measurementRequestToken++
            val token = measurementRequestToken
            measurementRequestStatus = "Solicitud enviada: " + measurementKindLabel(kind) +
                ". Esperando respuesta del reloj..."
            addLog("Solicitud HCI verificada: " + command.toHex() + " (inicio de " +
                measurementKindLabel(kind) + ")")
            window.decorView.postDelayed({
                if (gatt === current && measurementRequestToken == token && pendingMeasurement == kind) {
                    pendingMeasurement = null
                    measurementRequestToken++
                    measurementRequestStatus =
                        "Sin resultado tras 45 s; verifica el registro. No se reintentó automáticamente."
                    addLog(measurementRequestStatus)
                }
            }, 45000L)
        } catch (e: Exception) {
            pendingMeasurement = null
            measurementRequestToken++
            measurementRequestStatus = "Error enviando comando: " + (e.message ?: e.javaClass.simpleName)
            addLog(measurementRequestStatus)
        }
    }



    /** The same transport handles explicit tests and consented Android messages. */
    private fun sendNotificationTest() {
        enqueueWatchNotification(
            IncomingWatchNotice(
                WatchNotificationProtocol.Type.WHATSAPP,
                "Prueba",
                "Ultra3: mensaje de prueba"
            ),
            manual = true
        )
    }

    private fun handleIncomingWatchNotice(notice: IncomingWatchNotice) {
        if (!notificationForwardEnabled || !notificationAccessGranted) return
        if (!connected || !uartSubscribed) {
            notificationDroppedCount++
            return
        }
        enqueueWatchNotification(notice, manual = false)
    }

    private fun enqueueWatchNotification(notice: IncomingWatchNotice, manual: Boolean) {
        if (!connected || !uartSubscribed || gatt == null) {
            if (manual) notifyTestStatus = "Conecta el reloj y activa recepción UART"
            else notificationDroppedCount++
            return
        }
        if (pendingMeasurement != null || uartPendingEnabled != null || extraPendingEnabled != null) {
            if (manual) notifyTestStatus = "Hay una medición u operación BLE en curso"
            else notificationDroppedCount++
            return
        }
        if (notificationQueue.size >= maximumPendingNotifications) {
            if (manual) notifyTestStatus = "Cola de mensajes llena"
            else notificationDroppedCount++
            return
        }
        notificationQueue.addLast(notice)
        if (!notifyTestBusy) sendNextWatchNotification()
    }

    @SuppressLint("MissingPermission")
    private fun sendNextWatchNotification() {
        if (notifyTestBusy || notificationQueue.isEmpty()) return
        val current = gatt
        if (current == null || !connected || !uartSubscribed) {
            notificationDroppedCount += notificationQueue.size
            notificationQueue.clear()
            return
        }
        val tx = current.getService(UART_SERVICE)?.getCharacteristic(UART_WRITE)
        if (tx == null || tx.properties and
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE == 0) {
            notificationDroppedCount += notificationQueue.size
            notificationQueue.clear()
            notifyTestStatus = "El reloj no permite escritura UART"
            return
        }
        val notice = notificationQueue.removeFirst()
        val frame = WatchNotificationProtocol.pushNotification(notice.type, notice.text)
        val parts = WatchNotificationProtocol.fragments(frame)
        notifyTestToken++
        val token = notifyTestToken
        notifyTestBusy = true
        notifyTestStatus = "Enviando aviso: " + notice.source
        // Deliberately do not log message titles or bodies.
        addLog("Aviso para reloj: " + notice.source +
            " · CMD 02 SUB 12 · " + parts.size + " fragmentos")
        fun sendPart(index: Int) {
            if (token != notifyTestToken || gatt !== current || !connected) {
                if (token == notifyTestToken) {
                    notifyTestBusy = false
                    notifyTestStatus = "Envío interrumpido"
                }
                return
            }
            try {
                val ok = if (Build.VERSION.SDK_INT >= 33) {
                    current.writeCharacteristic(
                        tx, parts[index],
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    ) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    tx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    @Suppress("DEPRECATION")
                    tx.value = parts[index]
                    @Suppress("DEPRECATION")
                    current.writeCharacteristic(tx)
                }
                if (!ok) {
                    notifyTestBusy = false
                    if (notice.source != "Prueba") notificationDroppedCount++
                    notifyTestStatus = "Error transmitiendo aviso"
                    addLog("Fallo enviando fragmento de " + notice.source)
                    window.decorView.postDelayed({ sendNextWatchNotification() }, 200L)
                } else if (index + 1 < parts.size) {
                    window.decorView.postDelayed({ sendPart(index + 1) }, 160L)
                } else {
                    notifyTestBusy = false
                    if (notice.source != "Prueba") notificationForwardedCount++
                    notifyTestStatus = "Enviado por BLE: " + notice.source
                    addLog("Aviso BLE enviado: " + notice.source +
                        " (visualización en reloj no verificada)")
                    window.decorView.postDelayed({ sendNextWatchNotification() }, 200L)
                }
            } catch (e: Exception) {
                notifyTestBusy = false
                if (notice.source != "Prueba") notificationDroppedCount++
                notifyTestStatus = "Error Bluetooth: " + (e.message ?: e.javaClass.simpleName)
                addLog("Error enviando aviso " + notice.source)
                window.decorView.postDelayed({ sendNextWatchNotification() }, 200L)
            }
        }
        sendPart(0)
    }

    @SuppressLint("MissingPermission")
    private fun readBatteryAfterInitialization(current: BluetoothGatt) {
        if (current !== gatt || !connected) return
        val service = current.getService(UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb"))
        val characteristic = service?.getCharacteristic(
            UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        )
        if (characteristic != null &&
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
            try {
                if (!current.readCharacteristic(characteristic))
                    addLog("No se pudo iniciar lectura de batería")
            } catch (e: Exception) {
                addLog("Error lectura de batería: " + (e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun markActivityEvent(kind: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val message = "MARCADOR MANUAL: $kind (no envía órdenes al reloj)"
        addLog(message)
        dfFrameMessages.add(0, "$timestamp · >>> $message")
        while (dfFrameMessages.size > 40) dfFrameMessages.removeAt(dfFrameMessages.lastIndex)
    }

    private fun addLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        logLines.add(0, "$timestamp · $message")
        while (logLines.size > 80) logLines.removeAt(logLines.lastIndex)
    }

    override fun onDestroy() {
        notifyTestToken++
        notifyTestBusy = false
        notificationQueue.clear()
        WatchNotificationBridge.receiver = null
        WatchNotificationBridge.diagnostic = null
        intentionalDisconnect = true
        retryGeneration++
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
    notificationAccessGranted: Boolean, notificationForwardEnabled: Boolean,
    notificationAppAllowed: Map<String, Boolean>,
    notificationForwardedCount: Int, notificationDroppedCount: Int,
    onOpenNotificationAccess: () -> Unit,
    onNotificationMasterChange: (Boolean) -> Unit,
    onNotificationAppChange: (String, Boolean) -> Unit,
    savedMeasurements: List<StoredMeasurement>, onClearMeasurements: () -> Unit,
    uartState: String, uartSubscribed: Boolean, uartBusy: Boolean,
    uartCount: Int, uartMessages: List<String>,
    dfFrameCount: Int, dfVerifiedCount: Int, dfFrameMessages: List<String>,
    heartCandidate: String, pressureCandidate: String,
    measurementRequestStatus: String, measurementInProgress: Boolean,
    onRequestMeasurement: (ExperimentalVitalDecoder.Kind) -> Unit,
    notifyTestStatus: String, notifyTestBusy: Boolean, onNotifyTest: () -> Unit,
    extraState: String, extraSubscribed: Boolean, extraBusy: Boolean,
    extraCount: Int, extraMessages: List<String>,
    rssiFor: (String) -> Int?,
    onToggleUart: () -> Unit, onMarkEvent: (String) -> Unit, onToggleExtra: () -> Unit,
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
                icon = { Icon(Icons.Default.History, null) }, label = { Text("Historial") })
            NavigationBarItem(selected = tab == 3, onClick = { tab = 3 },
                icon = { Icon(Icons.Default.Notifications, null) }, label = { Text("Avisos") })
            NavigationBarItem(selected = tab == 4, onClick = { tab = 4 },
                icon = { Icon(Icons.Default.Refresh, null) }, label = { Text("Registro") })
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().background(Bg).padding(padding).padding(18.dp)) {
            Text("ULTRA 3", color = Green, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Text("Connect", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            when (tab) {
                0 -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    DeviceScreen(status, scanning, connected, devices, nameFor, selectedAddress,
                        battery, rssiFor, savedMeasurements, uartSubscribed, uartBusy,
                        services.any { it.uuid == UART_SERVICE }, onToggleUart,
                        measurementRequestStatus, measurementInProgress, onRequestMeasurement,
                        notifyTestStatus, notifyTestBusy, onNotifyTest,
                        onScan, onDirectConnect, onConnect, onDisconnect)
                }
                1 -> Column(
                    modifier = Modifier.fillMaxWidth().weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    ServicesScreen(
                        services, characteristicValue, connected, uartState, uartSubscribed,
                        uartBusy || extraBusy, uartCount, uartMessages,
                        dfFrameCount, dfVerifiedCount, dfFrameMessages, heartCandidate, pressureCandidate,
                        onToggleUart, onMarkEvent, extraState, extraSubscribed, extraCount,
                        extraMessages, onToggleExtra, onRead
                    )
                }
                2 -> Column(
                    modifier = Modifier.fillMaxWidth().weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    MeasurementHistoryScreen(savedMeasurements, onClearMeasurements)
                }
                3 -> Column(
                    modifier = Modifier.fillMaxWidth().weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    NotificationSettingsScreen(
                        notificationAccessGranted, notificationForwardEnabled,
                        notificationAppAllowed, notificationForwardedCount,
                        notificationDroppedCount, connected, uartSubscribed,
                        uartBusy || extraBusy, onOpenNotificationAccess,
                        onNotificationMasterChange, onNotificationAppChange, onToggleUart
                    )
                }
                else -> Column(
                    modifier = Modifier.fillMaxWidth().weight(1f)
                        .verticalScroll(rememberScrollState())
                ) { LogScreen(logLines) }
            }
        }
    }
}

@Composable
private fun DeviceScreen(
    status: String, scanning: Boolean, connected: Boolean, devices: List<BluetoothDevice>,
    nameFor: (String) -> String, selectedAddress: String?, battery: String,
    rssiFor: (String) -> Int?,
    savedMeasurements: List<StoredMeasurement>, uartSubscribed: Boolean,
    uartBusy: Boolean, uartAvailable: Boolean, onToggleUart: () -> Unit,
    measurementRequestStatus: String, measurementInProgress: Boolean,
    onRequestMeasurement: (ExperimentalVitalDecoder.Kind) -> Unit,
    notifyTestStatus: String, notifyTestBusy: Boolean, onNotifyTest: () -> Unit,
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
            // Short path back into the device on smaller phones: the list still
            // appears further down in the scrollable Dispositivo screen.
            val discoveredUltra3 = devices.firstOrNull {
                nameFor(it.address).equals("Ultra 3", ignoreCase = true)
            }
            if (!connected && discoveredUltra3 != null) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { onConnect(discoveredUltra3) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Bluetooth, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Conectar Ultra 3 detectado")
                }
            }
            if (!connected && selectedAddress != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { onDirectConnect(selectedAddress) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Reconectar último Ultra 3") }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Text("LECTURAS DEL RELOJ", color = Muted, fontSize = 11.sp, letterSpacing = 1.5.sp)
    Spacer(Modifier.height(7.dp))
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(15.dp)) {
            val latestHr = savedMeasurements.firstOrNull {
                it.kind == ExperimentalVitalDecoder.Kind.HEART_RATE
            }
            val latestBp = savedMeasurements.firstOrNull {
                it.kind == ExperimentalVitalDecoder.Kind.BLOOD_PRESSURE
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Pulso", color = Muted, fontSize = 12.sp)
                    Text(latestHr?.valueLabel ?: "Sin datos", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text(latestHr?.let { formatMeasurementTime(it.receivedAtMillis) } ?: "Aún no recibido",
                        color = Muted, fontSize = 10.sp)
                }
                Column(Modifier.weight(1f)) {
                    Text("Presión", color = Muted, fontSize = 12.sp)
                    Text(latestBp?.valueLabel ?: "Sin datos", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text(latestBp?.let { formatMeasurementTime(it.receivedAtMillis) } ?: "Aún no recibida",
                        color = Muted, fontSize = 10.sp)
                }
            }
            Spacer(Modifier.height(9.dp))
            Text(
                "Datos informados por el reloj, sin validación clínica. No usarlos para decisiones médicas.",
                color = Muted, fontSize = 11.sp
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                enabled = connected && uartAvailable && !uartBusy && !measurementInProgress,
                onClick = onToggleUart
            ) { Text(if (uartSubscribed) "Detener captura de lecturas" else "Activar recepción de lecturas") }
            Spacer(Modifier.height(8.dp))
            Text("SOLICITAR UNA MEDICIÓN", color = Green, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = connected && uartSubscribed && !uartBusy && !measurementInProgress,
                    modifier = Modifier.weight(1f),
                    onClick = { onRequestMeasurement(ExperimentalVitalDecoder.Kind.HEART_RATE) }
                ) { Text("Medir pulso", fontSize = 12.sp) }
                Button(
                    enabled = connected && uartSubscribed && !uartBusy && !measurementInProgress,
                    modifier = Modifier.weight(1f),
                    onClick = { onRequestMeasurement(ExperimentalVitalDecoder.Kind.BLOOD_PRESSURE) }
                ) { Text("Medir presión", fontSize = 12.sp) }
            }
            Text(measurementRequestStatus, color = Muted, fontSize = 11.sp)
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = Bg)
            Spacer(Modifier.height(10.dp))
            Text("PRUEBA DE NOTIFICACIONES", fontSize = 11.sp,
                color = Green, fontWeight = FontWeight.Bold)
            Text("Texto ficticio tipo WhatsApp, sin leer notificaciones personales.",
                fontSize = 11.sp, color = Muted)
            Button(
                enabled = connected && uartSubscribed && !uartBusy &&
                    !measurementInProgress && !notifyTestBusy,
                onClick = onNotifyTest,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (notifyTestBusy) "Enviando..." else "Enviar notificación de prueba") }
            Text(notifyTestStatus, fontSize = 11.sp, color = Muted)
            Text(
                "Comandos capturados de HryFine. Se envían solo cuando pulsas un botón.",
                color = Muted, fontSize = 10.sp
            )
        }
    }
    Spacer(Modifier.height(12.dp))
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
    // This list is part of a vertically scrollable device screen; nesting
    // LazyColumn here would receive unbounded height and break scrolling.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        orderedDevices.forEach { device ->
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
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun ServicesScreen(
    services: List<BluetoothGattService>, value: String, connected: Boolean,
    uartState: String, uartSubscribed: Boolean, uartBusy: Boolean,
    uartCount: Int, uartMessages: List<String>,
    dfFrameCount: Int, dfVerifiedCount: Int, dfFrameMessages: List<String>,
    heartCandidate: String, pressureCandidate: String,
    onToggleUart: () -> Unit, onMarkEvent: (String) -> Unit,
    extraState: String, extraSubscribed: Boolean, extraCount: Int,
    extraMessages: List<String>, onToggleExtra: () -> Unit,
    onRead: (BluetoothGattService, BluetoothGattCharacteristic) -> Unit
) {
    val context = LocalContext.current
    Text("LECTURAS EXPERIMENTALES", color = Muted, fontSize = 11.sp, letterSpacing = 1.5.sp)
    Spacer(Modifier.height(8.dp))
    Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text("Pulso candidato: $heartCandidate", fontWeight = FontWeight.SemiBold, color = Color.White)
            Spacer(Modifier.height(5.dp))
            Text("Presión candidata: $pressureCandidate", fontWeight = FontWeight.SemiBold, color = Color.White)
            Spacer(Modifier.height(8.dp))
            Text(
                "Son valores inferidos de tramas CMD 05 (SUB 04/05), no lecturas confirmadas ni datos para decisiones médicas. Compara con la pantalla del reloj.",
                color = Muted, fontSize = 11.sp
            )
        }
    }
    Spacer(Modifier.height(14.dp))
    Text("RECEPCIÓN UART", color = Muted, fontSize = 11.sp, letterSpacing = 1.5.sp)
    Spacer(Modifier.height(8.dp))
    Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(uartState, color = if (uartSubscribed) Green else Muted, fontWeight = FontWeight.SemiBold)
            Text("Fragmentos UART recibidos: $uartCount", fontSize = 13.sp, color = Muted)
            Text("Tramas DF: $dfFrameCount · válidas: $dfVerifiedCount", fontSize = 13.sp, color = Green)
            Spacer(Modifier.height(8.dp))
            Button(
                enabled = connected && !uartBusy && services.any { it.uuid == UART_SERVICE },
                onClick = onToggleUart
            ) {
                Text(if (uartSubscribed) "Detener recepción" else "Activar recepción UART")
            }
            Text(
                "Escucha datos enviados por el reloj. Las etiquetas de salud son hipótesis, NO mediciones confirmadas. Sin comandos ni OTA.",
                fontSize = 11.sp, color = Muted
            )
            Spacer(Modifier.height(8.dp))
            Text("Antes de iniciar una medición EN EL RELOJ, marca el evento aquí:", fontSize = 12.sp, color = Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    enabled = connected && uartSubscribed,
                    onClick = { onMarkEvent("Inicio medición de pulso") },
                    modifier = Modifier.weight(1f)
                ) { Text("Marcar pulso", fontSize = 12.sp) }
                OutlinedButton(
                    enabled = connected && uartSubscribed,
                    onClick = { onMarkEvent("Inicio medición de presión") },
                    modifier = Modifier.weight(1f)
                ) { Text("Marcar presión", fontSize = 12.sp) }
            }
            if (dfFrameMessages.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Button(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(
                        "Ultra 3 Connect · Tramas DF",
                        dfFrameMessages.asReversed().joinToString("\n\n")
                    ))
                }) { Text("Copiar tramas DF") }
                dfFrameMessages.take(4).forEach { message ->
                    Text(message, fontSize = 11.sp, color = Green)
                    Spacer(Modifier.height(6.dp))
                }
            }
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
    Text("CANAL EXPERIMENTAL FF14", color = Muted, fontSize = 11.sp, letterSpacing = 1.5.sp)
    Spacer(Modifier.height(8.dp))
    Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(extraState, color = if (extraSubscribed) Green else Muted, fontWeight = FontWeight.SemiBold)
            Text("Paquetes FF14 recibidos: $extraCount", fontSize = 13.sp, color = Muted)
            Spacer(Modifier.height(8.dp))
            Button(
                enabled = connected && !uartBusy && services.any { it.uuid == EXTRA_SERVICE },
                onClick = onToggleExtra
            ) {
                Text(if (extraSubscribed) "Detener FF14" else "Activar recepción FF14")
            }
            Text("Escucha un segundo canal de datos sin enviar comandos al reloj.", fontSize = 11.sp, color = Muted)
            extraMessages.take(3).forEach { packet ->
                Spacer(Modifier.height(6.dp))
                Text(packet, fontSize = 11.sp, color = Green)
            }
        }
    }
    Spacer(Modifier.height(14.dp))
    Text("SERVICIOS GATT", color = Muted, fontSize = 11.sp, letterSpacing = 1.5.sp)
    Spacer(Modifier.height(8.dp))
    Text(value, color = Green, fontSize = 12.sp)
    Spacer(Modifier.height(8.dp))
    if (services.isEmpty()) Text("Conecta el reloj para descubrir sus servicios.", color = Muted)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        services.forEach { service ->
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
private fun NotificationSettingsScreen(
    accessGranted: Boolean,
    masterEnabled: Boolean,
    appAllowed: Map<String, Boolean>,
    forwardedCount: Int,
    droppedCount: Int,
    connected: Boolean,
    uartSubscribed: Boolean,
    uartBusy: Boolean,
    onOpenSettings: () -> Unit,
    onMasterChange: (Boolean) -> Unit,
    onAppChange: (String, Boolean) -> Unit,
    onToggleUart: () -> Unit
) {
    Text("NOTIFICACIONES DE ANDROID", color = Muted, fontSize = 11.sp,
        letterSpacing = 1.5.sp)
    Spacer(Modifier.height(8.dp))
    Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(
                if (accessGranted) "Acceso concedido en Android"
                else "Acceso especial pendiente",
                color = if (accessGranted) Green else Muted,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "El permiso permite leer notificaciones en Android, pero solo se envían " +
                    "al reloj las aplicaciones que actives aquí. Por defecto, ninguna.",
                color = Muted, fontSize = 12.sp
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text(if (accessGranted) "Administrar permiso de Android" else "Conceder acceso a notificaciones")
            }
            Spacer(Modifier.height(7.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Reenvío activado", fontWeight = FontWeight.Medium)
                    Text("Control general", fontSize = 11.sp, color = Muted)
                }
                Switch(
                    checked = masterEnabled,
                    enabled = accessGranted,
                    onCheckedChange = onMasterChange
                )
            }
            if (!accessGranted) {
                Text("Activa el acceso en los ajustes de Android y vuelve a esta pantalla.",
                    color = Muted, fontSize = 11.sp)
            }
        }
    }
    Spacer(Modifier.height(14.dp))
    Text("APLICACIONES PERMITIDAS", color = Muted, fontSize = 11.sp, letterSpacing = 1.5.sp)
    Spacer(Modifier.height(7.dp))
    WatchNotificationSources.supported.forEach { app ->
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(app.name, fontWeight = FontWeight.SemiBold)
                    Text("Enviar mensajes de esta aplicación", fontSize = 11.sp, color = Muted)
                }
                Switch(
                    checked = appAllowed[app.key] == true,
                    enabled = accessGranted,
                    onCheckedChange = { onAppChange(app.key, it) }
                )
            }
        }
        Spacer(Modifier.height(7.dp))
    }
    Spacer(Modifier.height(10.dp))
    Text("ESTADO DEL ENVÍO", color = Muted, fontSize = 11.sp, letterSpacing = 1.5.sp)
    Spacer(Modifier.height(7.dp))
    Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text("Bluetooth: " + if (connected) "Conectado" else "Desconectado")
            Text("UART: " + if (uartSubscribed) "Activado" else "Sin activar")
            Text("Avisos enviados por BLE: $forwardedCount", color = Green)
            Text("Avisos descartados por conexión u operación: $droppedCount",
                fontSize = 12.sp, color = Muted)
            Spacer(Modifier.height(8.dp))
            Button(
                enabled = connected && !uartBusy,
                onClick = onToggleUart,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (uartSubscribed) "Detener recepción UART" else "Activar recepción UART") }
        }
    }
    Spacer(Modifier.height(14.dp))
    Text(
        "Privacidad: se omiten avisos con códigos de verificación, PIN y contraseñas. " +
            "No guardamos el contenido de los mensajes. El historial médico es independiente. " +
            "En esta versión el Bluetooth permanece administrado por la pantalla de la app: " +
            "la entrega en segundo plano todavía no está garantizada.",
        fontSize = 12.sp, color = Muted
    )
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun MeasurementHistoryScreen(
    measurements: List<StoredMeasurement>,
    onClearMeasurements: () -> Unit
) {
    var confirmClear by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Text("HISTORIAL LOCAL", color = Muted, fontSize = 11.sp, letterSpacing = 1.5.sp)
    Spacer(Modifier.height(8.dp))
    Text(
        "Últimas 100 lecturas del reloj. Se almacenan solamente en este teléfono.",
        color = Muted, fontSize = 12.sp
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(enabled = measurements.isNotEmpty(), onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = measurements.asReversed().joinToString("\n") { entry ->
                "${formatMeasurementTime(entry.receivedAtMillis)} | ${measurementKindLabel(entry.kind)} | ${entry.valueLabel} | ${entry.packetHex}"
            }
            clipboard.setPrimaryClip(ClipData.newPlainText("Ultra 3 Connect · Historial", text))
        }) { Text("Copiar") }
        OutlinedButton(enabled = measurements.isNotEmpty(), onClick = { confirmClear = true }) {
            Text("Borrar todo")
        }
    }
    Spacer(Modifier.height(10.dp))
    if (measurements.isEmpty()) {
        Text("No hay mediciones guardadas. Conecta el reloj, activa UART y realiza una medición.", color = Muted)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        measurements.forEach { item ->
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(measurementKindLabel(item.kind), color = Muted, fontSize = 12.sp)
                    Text(item.valueLabel, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Green)
                    Text(formatMeasurementTime(item.receivedAtMillis), fontSize = 11.sp, color = Muted)
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Eliminar historial") },
            text = { Text("Se borrarán las mediciones guardadas en este teléfono. Esta acción no se puede deshacer.") },
            confirmButton = {
                TextButton(onClick = { onClearMeasurements(); confirmClear = false }) {
                    Text("Eliminar")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancelar") }
            }
        )
    }
}

private fun measurementKindLabel(kind: ExperimentalVitalDecoder.Kind): String = when (kind) {
    ExperimentalVitalDecoder.Kind.HEART_RATE -> "Frecuencia cardíaca"
    ExperimentalVitalDecoder.Kind.BLOOD_PRESSURE -> "Presión arterial"
}

private fun formatMeasurementTime(timeMs: Long): String =
    SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date(timeMs))

@Composable
private fun LogScreen(lines: List<String>) {
    val context = LocalContext.current
    Text("EVENTOS BLUETOOTH", color = Muted, fontSize = 11.sp, letterSpacing = 1.5.sp)
    Spacer(Modifier.height(8.dp))
    Button(enabled = lines.isNotEmpty(), onClick = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText("Ultra 3 Connect · Registro BLE", lines.asReversed().joinToString("\n"))
        )
    }) { Text("Copiar registro") }
    Spacer(Modifier.height(8.dp))
    if (lines.isEmpty()) Text("Todavía no hay eventos.", color = Muted)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        lines.forEach { line ->
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
