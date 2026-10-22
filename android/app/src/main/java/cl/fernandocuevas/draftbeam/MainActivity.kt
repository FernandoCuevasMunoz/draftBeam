package cl.fernandocuevas.draftbeam

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cl.fernandocuevas.draftbeam.ui.theme.DraftBeamTheme
import cl.fernandocuevas.draftbeam.ui.theme.DraftBeamColors
import java.util.UUID

private data class PlanSnapshot(
    val segments: List<WallSegment>,
    val notes: List<PlanNote>,
    val selectedStartPoint: PointMm?
)

private data class PendingConnection(
    val start: PointMm,
    val freeEnd: PointMm,
    val target: PointMm
)

data class PendingEndpointMove(
    val segmentIndex: Int,
    val endpoint: WallEndpoint,
    val origin: PointMm,
    val destination: PointMm
)

data class LengthMeasurement(val id: Int, val millimeters: Long)

private class CreateDxfInDownloads : ActivityResultContract<String, Uri?>() {
    override fun createIntent(context: Context, input: String): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/dxf"
            putExtra(Intent.EXTRA_TITLE, input)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                putExtra(
                    DocumentsContract.EXTRA_INITIAL_URI,
                    DocumentsContract.buildDocumentUri(
                        "com.android.externalstorage.documents",
                        "primary:Download"
                    )
                )
            }
        }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == android.app.Activity.RESULT_OK) intent?.data else null
}

class MainActivity : ComponentActivity() {
    private val status = mutableStateOf("Listo para buscar el medidor.")
    private val devices = mutableStateListOf<ScannedMeter>()
    private val notifications = mutableStateListOf<MeasurementNotification>()
    private val wallSegments = mutableStateListOf<WallSegment>()
    private val planNotes = mutableStateListOf<PlanNote>()
    private val undoHistory = mutableStateListOf<PlanSnapshot>()
    private val latestMeasurementMm = mutableStateOf<Long?>(null)
    private val newLengthFieldFocused = mutableStateOf(false)
    private val lengthMeasurementEvent = mutableStateOf<LengthMeasurement?>(null)
    private var lengthMeasurementEventId = 0
    private val selectedAngleDegrees = mutableStateOf(WallDirection.X_POSITIVE.angleDegrees)
    private val selectedWallIndex = mutableStateOf<Int?>(null)
    private val selectedNoteIndex = mutableStateOf<Int?>(null)
    private val wallEditorOpen = mutableStateOf(false)
    private val selectedStartPoint = mutableStateOf<PointMm?>(null)
    private val pendingEndpointMove = mutableStateOf<PendingEndpointMove?>(null)
    private val selectedDeviceName = mutableStateOf<String?>(null)
    private val connectingDeviceName = mutableStateOf<String?>(null)
    private val dxfImportRevision = mutableIntStateOf(0)
    private var currentDxfUri: Uri? = null

    private val bluetoothAdapter by lazy {
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var scanner: BluetoothLeScanner? = null
    private var connectedGatt: BluetoothGatt? = null

    private val stopScanRunnable = Runnable { stopScan("Búsqueda finalizada.") }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
            val device = result.device
            val label = try {
                device.name?.takeIf { it.isNotBlank() } ?: "Medidor BLE"
            } catch (_: SecurityException) {
                "Medidor BLE"
            }

            runOnUiThread {
                if (devices.none { it.device.address == device.address }) {
                    devices.add(ScannedMeter(device, label))
                }
                status.value = "Medidor encontrado. Toca su nombre para conectar."
            }
        }

        override fun onScanFailed(errorCode: Int) {
            runOnUiThread { status.value = "Falló el escaneo BLE (código $errorCode)." }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, statusCode: Int, newState: Int) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread {
                    status.value = "Error de conexión GATT: $statusCode."
                    selectedDeviceName.value = null
                    connectingDeviceName.value = null
                }
                gatt.close()
                if (connectedGatt === gatt) connectedGatt = null
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    runOnUiThread { status.value = "Conectado a ${connectingDeviceName.value ?: "medidor"}. Buscando características BLE…" }
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    gatt.close()
                    if (connectedGatt === gatt) connectedGatt = null
                    runOnUiThread {
                        selectedDeviceName.value = null
                        connectingDeviceName.value = null
                        status.value = "Desconectado del medidor."
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, statusCode: Int) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread {
                    selectedDeviceName.value = null
                    connectingDeviceName.value = null
                    status.value = "No se pudieron descubrir los servicios BLE."
                }
                return
            }

            val characteristic = gatt
                .getService(METER_SERVICE_UUID)
                ?.getCharacteristic(MEASUREMENT_CHARACTERISTIC_UUID)

            if (characteristic == null) {
                runOnUiThread {
                    selectedDeviceName.value = null
                    connectingDeviceName.value = null
                    status.value = "No se encontró la característica de medición FFB2."
                }
                return
            }

            val notificationEnabled = gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(CCCD_UUID)
            if (!notificationEnabled || descriptor == null) {
                runOnUiThread {
                    selectedDeviceName.value = null
                    connectingDeviceName.value = null
                    status.value = "No se pudo preparar la suscripción a FFB2."
                }
                return
            }

            val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val writeStarted = if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = value
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }

            if (!writeStarted) {
                runOnUiThread {
                    selectedDeviceName.value = null
                    connectingDeviceName.value = null
                    status.value = "No se pudo activar la notificación de FFB2."
                }
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            statusCode: Int
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                runOnUiThread {
                    status.value = if (statusCode == BluetoothGatt.GATT_SUCCESS) {
                        selectedDeviceName.value = connectingDeviceName.value ?: "Medidor BLE"
                        connectingDeviceName.value = null
                        "Suscrito a FFB2. Pulsa el botón de medición del telémetro."
                    } else {
                        selectedDeviceName.value = null
                        connectingDeviceName.value = null
                        "Falló la suscripción a FFB2 (código $statusCode)."
                    }
                }
            }
        }

        @Deprecated("Usado en Android anterior a 13")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            val value = characteristic.value?.copyOf() ?: return
            onMeasurementNotification(characteristic, value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            onMeasurementNotification(characteristic, value.copyOf())
        }
    }

    private fun onMeasurementNotification(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        if (characteristic.uuid != MEASUREMENT_CHARACTERISTIC_UUID) return
        val hex = value.joinToString(" ") { byte -> "%02X".format(byte.toInt() and 0xFF) }
        val decoded = MeasurementParser.parse(value)
        runOnUiThread {
            latestMeasurementMm.value = (decoded as? MeasurementParseResult.Success)?.millimeters
            notifications.add(
                0,
                when (decoded) {
                    is MeasurementParseResult.Success -> MeasurementNotification(
                        rawHex = hex,
                        millimeters = decoded.millimeters
                    )
                    is MeasurementParseResult.Invalid -> MeasurementNotification(
                        rawHex = hex,
                        parseError = decoded.reason
                    )
                }
            )
            while (notifications.size > 30) notifications.removeAt(notifications.lastIndex)
            status.value = when (decoded) {
                is MeasurementParseResult.Success -> {
                    if (newLengthFieldFocused.value) {
                        lengthMeasurementEvent.value = LengthMeasurement(++lengthMeasurementEventId, decoded.millimeters)
                        "Medida recibida para Nuevo largo: ${decoded.millimeters} mm."
                    } else {
                        addWallForMeasurement(decoded.millimeters)
                    }
                }
                is MeasurementParseResult.Invalid -> "Notificación recibida, pero no se pudo interpretar."
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) restoreLastOpenedDxf()

        setContent {
            DraftBeamTheme {
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { permissions ->
                    if (permissions.values.all { it }) startScan()
                    else status.value = "Se necesitan permisos Bluetooth para buscar el medidor."
                }
                val saveDxfLauncher = rememberLauncherForActivityResult(CreateDxfInDownloads()) { uri ->
                    uri?.let {
                        persistDocumentPermissions(it)
                        saveDxf(it, associateDocument = true)
                    }
                }
                val loadDxfLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri ->
                    uri?.let { selectedUri ->
                        persistDocumentPermissions(selectedUri)
                        if (loadDxf(selectedUri)) associateDxfUri(selectedUri)
                    }
                }

                DraftBeamScreen(
                    status = status.value,
                    devices = devices.toList(),
                    notifications = notifications.toList(),
                    wallSegments = wallSegments.toList(),
                    notes = planNotes.toList(),
                    canUndo = undoHistory.isNotEmpty(),
                    latestMeasurementMm = latestMeasurementMm.value,
                    selectedAngleDegrees = selectedAngleDegrees.value,
                    selectedWallIndex = selectedWallIndex.value,
                    selectedNoteIndex = selectedNoteIndex.value,
                    wallEditorOpen = wallEditorOpen.value,
                    pendingEndpointMove = pendingEndpointMove.value,
                    lengthMeasurementEvent = lengthMeasurementEvent.value,
                    selectedStartPoint = selectedStartPoint.value,
                    selectedDeviceName = selectedDeviceName.value,
                    connectingDeviceName = connectingDeviceName.value,
                    dxfImportRevision = dxfImportRevision.value,
                    onSaveDxfClick = {
                        val openedUri = currentDxfUri
                        if (openedUri != null && hasPersistedWritePermission(openedUri)) {
                            if (!saveDxf(openedUri)) saveDxfLauncher.launch("draftBeam.dxf")
                        } else {
                            if (openedUri != null) {
                                status.value = "El archivo abierto no permite guardar cambios; elige una ubicación nueva."
                            }
                            saveDxfLauncher.launch("draftBeam.dxf")
                        }
                    },
                    onLoadDxfClick = {
                        loadDxfLauncher.launch(
                            arrayOf("application/dxf", "application/x-dxf", "image/vnd.dxf", "application/octet-stream", "*/*")
                        )
                    },
                    onScanClick = {
                        val missingPermissions = requiredPermissions()
                            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                        if (missingPermissions.isEmpty()) startScan()
                        else permissionLauncher.launch(missingPermissions.toTypedArray())
                    },
                    onDeviceClick = ::connectToDevice,
                    onDisconnectClick = ::disconnect,
                    onDirectionSelected = { selectedAngleDegrees.value = it.angleDegrees },
                    onDeleteWallClick = ::deleteSelectedWall,
                    onUndoClick = ::undoLastPlanAction,
                    onWallSelected = {
                        selectedWallIndex.value = it
                        selectedNoteIndex.value = null
                        wallEditorOpen.value = false
                    },
                    onNoteSelected = { index ->
                        selectedNoteIndex.value = index
                        selectedWallIndex.value = null
                        wallEditorOpen.value = false
                    },
                    onNotePlaced = { text, position -> addNote(text, position).also { status.value = it } },
                    onNoteEdited = { index, text -> updateNote(index, text).also { status.value = it } },
                    onEditWallClick = { wallEditorOpen.value = selectedWallIndex.value != null },
                    onCreateWall = { start, angle, length, width, side ->
                        addManualWall(start, angle, length, width, side).also { status.value = it }
                    },
                    onEndpointMoveRequested = ::requestEndpointMove,
                    onMoveEndpoint = ::moveEndpoint,
                    onDismissEndpointMove = { pendingEndpointMove.value = null },
                    onSaveWallProperties = { index, length, width, side ->
                        updateWallProperties(index, length, width, side).also { status.value = it }
                    },
                    onNewLengthFieldFocusChanged = { newLengthFieldFocused.value = it },
                    onLengthMeasurementConsumed = { eventId ->
                        if (lengthMeasurementEvent.value?.id == eventId) lengthMeasurementEvent.value = null
                    },
                    onDismissWallEditor = {
                        newLengthFieldFocused.value = false
                        wallEditorOpen.value = false
                    },
                    onFingerDrawn = ::addWallFromFinger,
                    onStartPointSelected = { point ->
                        selectedStartPoint.value = point
                        selectedWallIndex.value = null
                        selectedNoteIndex.value = null
                        wallEditorOpen.value = false
                        status.value = "Punto de continuación seleccionado: ${point.formatCoordinates()} mm."
                    }
                )
            }
        }
    }

    private fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    @Suppress("MissingPermission")
    private fun startScan() {
        if (!hasBluetoothPermissions()) {
            status.value = "Faltan permisos Bluetooth. Toca Buscar medidor para concederlos."
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            status.value = "Activa Bluetooth en el teléfono y vuelve a buscar."
            return
        }

        val bleScanner = bluetoothAdapter.bluetoothLeScanner
        if (bleScanner == null) {
            status.value = "El escáner BLE no está disponible en este teléfono."
            return
        }

        devices.clear()
        scanner = bleScanner
        status.value = "Buscando medidores BLE durante 12 segundos…"
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(METER_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            bleScanner.startScan(listOf(filter), settings, scanCallback)
            mainHandler.removeCallbacks(stopScanRunnable)
            mainHandler.postDelayed(stopScanRunnable, SCAN_DURATION_MS)
        } catch (exception: SecurityException) {
            status.value = "Android no autorizó el escaneo BLE: ${exception.message}"
        }
    }

    @Suppress("MissingPermission")
    private fun stopScan(message: String) {
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
            // El permiso pudo revocarse mientras el escaneo estaba activo.
        }
        scanner = null
        if (devices.isEmpty() && status.value.startsWith("Buscando")) {
            status.value = "$message No se encontraron dispositivos con el servicio FFB0."
        } else if (status.value.startsWith("Buscando")) {
            status.value = message
        }
    }

    @Suppress("MissingPermission")
    private fun connectToDevice(meter: ScannedMeter) {
        if (!hasBluetoothPermissions()) {
            status.value = "Faltan permisos Bluetooth para conectar."
            return
        }
        stopScan("Conectando al medidor…")
        connectedGatt?.close()
        notifications.clear()
        selectedDeviceName.value = null
        connectingDeviceName.value = meter.name
        status.value = "Conectando a ${meter.name}…"
        connectedGatt = meter.device.connectGatt(this, false, gattCallback)
    }

    @Suppress("MissingPermission")
    private fun disconnect() {
        connectedGatt?.disconnect()
        status.value = "Desconectando…"
    }

    private fun addWallForMeasurement(measurement: Long): String {
        try {
            val updatedSegments = PlanGeometry.appendSegment(
                existingSegments = wallSegments.toList(),
                angleDegrees = selectedAngleDegrees.value,
                lengthMm = measurement,
                startPoint = selectedStartPoint.value
            )
            pushPlanSnapshot()
            wallSegments.add(updatedSegments.last())
            val endpoint = updatedSegments.last().end
            selectedStartPoint.value = endpoint
            selectedWallIndex.value = null
            selectedNoteIndex.value = null
            wallEditorOpen.value = false
            return "Muro agregado automáticamente: $measurement mm a ${selectedAngleDegrees.value.formatAngle()}°. Extremo ${endpoint.formatCoordinates()} mm."
        } catch (exception: IllegalArgumentException) {
            return exception.message ?: "La medida no es válida para crear un muro."
        } catch (_: ArithmeticException) {
            return "El plano excede el rango de coordenadas permitido."
        }
    }

    private fun addManualWall(
        start: PointMm,
        angleDegrees: Double,
        lengthMm: Long,
        widthMm: Long,
        growthSide: WallGrowthSide
    ): String {
        return try {
            val segment = PlanGeometry.createWall(
                existingSegments = wallSegments.toList(),
                angleDegrees = angleDegrees,
                lengthMm = lengthMm,
                startPoint = start,
                widthMm = widthMm,
                growthSide = growthSide
            )
            pushPlanSnapshot()
            wallSegments.add(segment)
            selectedStartPoint.value = segment.end
            selectedWallIndex.value = null
            selectedNoteIndex.value = null
            wallEditorOpen.value = false
            "Muro creado: ${segment.lengthMm} mm, ancho ${segment.widthMm} mm, ${segment.growthSide.axisLabel(segment.angleDegrees)}."
        } catch (exception: IllegalArgumentException) {
            exception.message ?: "No se pudo crear el muro."
        } catch (_: ArithmeticException) {
            "El plano excede el rango de coordenadas permitido."
        }
    }

    private fun addNote(rawText: String, position: PointMm): String {
        return try {
            val note = PlanNote(rawText.trim(), position)
            pushPlanSnapshot()
            planNotes.add(note)
            selectedNoteIndex.value = planNotes.lastIndex
            selectedWallIndex.value = null
            "Nota agregada al plano."
        } catch (exception: IllegalArgumentException) {
            exception.message ?: "No se pudo agregar la nota."
        }
    }

    private fun updateNote(index: Int, rawText: String): String {
        val oldNote = planNotes.getOrNull(index) ?: return "La nota seleccionada ya no existe."
        return try {
            val updatedNote = oldNote.copy(text = rawText.trim())
            if (updatedNote != oldNote) {
                pushPlanSnapshot()
                planNotes[index] = updatedNote
            }
            "Nota actualizada."
        } catch (exception: IllegalArgumentException) {
            exception.message ?: "No se pudo actualizar la nota."
        }
    }

    private fun addWallFromFinger(start: PointMm, draggedEnd: PointMm) {
        try {
            val segment = PlanGeometry.segmentFromDrag(start, draggedEnd)
            pushPlanSnapshot()
            wallSegments.add(segment)
            selectedStartPoint.value = segment.end
            selectedAngleDegrees.value = segment.angleDegrees
            selectedWallIndex.value = null
            selectedNoteIndex.value = null
            wallEditorOpen.value = false
            status.value = "Muro dibujado: ${segment.lengthMm} mm a ${segment.angleDegrees.formatAngle()}°."
        } catch (exception: IllegalArgumentException) {
            status.value = exception.message ?: "No se pudo crear el muro."
        }
    }

    private fun requestEndpointMove(
        index: Int,
        endpoint: WallEndpoint,
        origin: PointMm,
        destination: PointMm
    ) {
        if (PlanGeometry.endpointUseCount(wallSegments, origin) > 1) {
            pendingEndpointMove.value = PendingEndpointMove(index, endpoint, origin, destination)
        } else {
            moveEndpoint(PendingEndpointMove(index, endpoint, origin, destination), moveConnected = false)
        }
    }

    private fun moveEndpoint(move: PendingEndpointMove, moveConnected: Boolean) {
        try {
            val before = wallSegments.toList()
            val updatedSegments = PlanGeometry.moveEndpoint(
                existingSegments = before,
                index = move.segmentIndex,
                endpoint = move.endpoint,
                destination = move.destination,
                moveConnectedEndpoints = moveConnected
            )
            if (updatedSegments != before) {
                pushPlanSnapshot()
                wallSegments.clear()
                wallSegments.addAll(updatedSegments)
                if (selectedStartPoint.value == move.origin) selectedStartPoint.value = move.destination
            }
            pendingEndpointMove.value = null
            status.value = if (moveConnected) "Unión movida." else "Extremo del muro movido."
        } catch (exception: IllegalArgumentException) {
            pendingEndpointMove.value = null
            status.value = exception.message ?: "No se pudo mover el extremo del muro."
        }
    }

    private fun updateWallProperties(
        index: Int,
        lengthMm: Long,
        widthMm: Long,
        side: WallGrowthSide
    ): String {
        return try {
            val before = wallSegments.toList()
            val previousEnd = before.getOrNull(index)?.end
            val withNewLength = PlanGeometry.updateSegmentLength(before, index, lengthMm)
            val updatedSegments = PlanGeometry.updateSegmentWall(withNewLength, index, widthMm, side)
            if (updatedSegments != before) pushPlanSnapshot()
            wallSegments.clear()
            wallSegments.addAll(updatedSegments)
            if (previousEnd != null && selectedStartPoint.value == previousEnd) {
                selectedStartPoint.value = updatedSegments[index].end
            }
            "Muro actualizado: ${lengthMm} mm de largo y ${widthMm} mm de ancho."
        } catch (exception: IllegalArgumentException) {
            exception.message ?: "No se pudo actualizar el muro."
        }
    }

    private fun saveDxf(uri: Uri, associateDocument: Boolean = false): Boolean {
        try {
            val output = contentResolver.openOutputStream(uri, "wt")
                ?: throw IllegalStateException("Android no pudo abrir el archivo para escritura.")
            output.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(DxfCodec.encode(wallSegments.toList(), planNotes.toList()))
            }
            if (associateDocument) associateDxfUri(uri)
            status.value = if (uri == currentDxfUri && !associateDocument) {
                "Cambios guardados en el archivo DXF abierto."
            } else {
                "Plano guardado como DXF en la ubicación elegida."
            }
            return true
        } catch (exception: Exception) {
            if (!associateDocument && uri == currentDxfUri) {
                status.value = "No se pudo actualizar el archivo; elige dónde guardar una copia."
            } else {
                status.value = "No se pudo guardar el DXF: ${exception.message ?: "error de archivo"}"
            }
            return false
        }
    }

    private fun loadDxf(uri: Uri): Boolean {
        try {
            val input = contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Android no pudo abrir el archivo seleccionado.")
            val contents = input.bufferedReader(Charsets.UTF_8).use { reader ->
                val text = StringBuilder()
                val buffer = CharArray(8_192)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    require(text.length + count <= MAX_DXF_IMPORT_CHARS) {
                        "El DXF supera el límite de 16 MB."
                    }
                    text.append(buffer, 0, count)
                }
                text.toString()
            }
            val imported = DxfCodec.decode(contents)
            val before = wallSegments.toList()
            if (before != imported.segments || planNotes.toList() != imported.notes) pushPlanSnapshot()
            wallSegments.clear()
            wallSegments.addAll(imported.segments)
            planNotes.clear()
            planNotes.addAll(imported.notes)
            selectedWallIndex.value = null
            selectedNoteIndex.value = null
            wallEditorOpen.value = false
            pendingEndpointMove.value = null
            selectedStartPoint.value = imported.segments.lastOrNull()?.end
            dxfImportRevision.value++
            status.value = buildString {
                append("DXF cargado: ${imported.segments.size} entidades editables.")
                imported.warnings.forEach { append(" ").append(it) }
            }
            return true
        } catch (exception: Exception) {
            status.value = "No se pudo cargar el DXF: ${exception.message ?: "archivo no válido"}"
            return false
        }
    }

    private fun restoreLastOpenedDxf() {
        val uri = getSharedPreferences(DXF_PREFERENCES, MODE_PRIVATE)
            .getString(DXF_URI_KEY, null)
            ?.let(Uri::parse)
            ?: return
        val permission = contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
        if (permission?.isReadPermission != true) {
            getSharedPreferences(DXF_PREFERENCES, MODE_PRIVATE).edit().remove(DXF_URI_KEY).apply()
            return
        }
        persistDocumentPermissions(uri)
        if (loadDxf(uri)) associateDxfUri(uri)
    }

    private fun persistDocumentPermissions(uri: Uri) {
        listOf(Intent.FLAG_GRANT_READ_URI_PERMISSION, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            .forEach { flag ->
                try {
                    contentResolver.takePersistableUriPermission(uri, flag)
                } catch (_: SecurityException) {
                    // This provider may not grant persistence or this access mode.
                } catch (_: IllegalArgumentException) {
                    // The picker result did not include a persistable grant.
                }
            }
    }

    private fun hasPersistedWritePermission(uri: Uri): Boolean =
        contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }

    private fun associateDxfUri(uri: Uri) {
        currentDxfUri = uri
        val permissions = contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
        if (permissions?.isReadPermission == true) {
            getSharedPreferences(DXF_PREFERENCES, MODE_PRIVATE).edit()
                .putString(DXF_URI_KEY, uri.toString())
                .apply()
        } else {
            getSharedPreferences(DXF_PREFERENCES, MODE_PRIVATE).edit().remove(DXF_URI_KEY).apply()
        }
    }

    private fun deleteSelectedWall() {
        selectedNoteIndex.value?.let { index ->
            if (planNotes.getOrNull(index) != null) {
                pushPlanSnapshot()
                planNotes.removeAt(index)
                selectedNoteIndex.value = null
                status.value = "Nota eliminada."
            }
            return
        }
        val index = selectedWallIndex.value ?: return
        try {
            val updatedSegments = PlanGeometry.removeSegment(wallSegments.toList(), index)
            pushPlanSnapshot()
            wallSegments.clear()
            wallSegments.addAll(updatedSegments)
            selectedWallIndex.value = null
            wallEditorOpen.value = false
            if (selectedStartPoint.value != null &&
                selectedStartPoint.value != PointMm(0.0, 0.0) &&
                updatedSegments.none { it.start == selectedStartPoint.value || it.end == selectedStartPoint.value }
            ) {
                selectedStartPoint.value = updatedSegments.lastOrNull()?.end
            }
            status.value = "Muro eliminado. Las demás líneas conservaron su posición."
        } catch (exception: IllegalArgumentException) {
            selectedWallIndex.value = null
            status.value = exception.message ?: "No se pudo eliminar el muro seleccionado."
        }
    }

    private fun pushPlanSnapshot() {
        undoHistory.add(
            PlanSnapshot(
                segments = wallSegments.toList(),
                notes = planNotes.toList(),
                selectedStartPoint = selectedStartPoint.value
            )
        )
    }

    private fun undoLastPlanAction() {
        val previous = undoHistory.removeLastOrNull() ?: return
        wallSegments.clear()
        wallSegments.addAll(previous.segments)
        planNotes.clear()
        planNotes.addAll(previous.notes)
        selectedStartPoint.value = previous.selectedStartPoint
        selectedWallIndex.value = null
        selectedNoteIndex.value = null
        wallEditorOpen.value = false
        pendingEndpointMove.value = null
        status.value = "Última acción del plano deshecha."
    }

    private fun hasBluetoothPermissions(): Boolean = requiredPermissions().all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(stopScanRunnable)
        try {
            scanner?.stopScan(scanCallback)
            connectedGatt?.close()
        } catch (_: SecurityException) {
            // La actividad se está cerrando; no mantener recursos BLE abiertos.
        }
        scanner = null
        connectedGatt = null
        super.onDestroy()
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun DraftBeamScreen(
    status: String,
    devices: List<ScannedMeter>,
    notifications: List<MeasurementNotification>,
    wallSegments: List<WallSegment>,
    notes: List<PlanNote>,
    canUndo: Boolean,
    latestMeasurementMm: Long?,
    selectedAngleDegrees: Double,
    selectedWallIndex: Int?,
    selectedNoteIndex: Int?,
    wallEditorOpen: Boolean,
    pendingEndpointMove: PendingEndpointMove?,
    lengthMeasurementEvent: LengthMeasurement?,
    selectedStartPoint: PointMm?,
    selectedDeviceName: String?,
    connectingDeviceName: String?,
    dxfImportRevision: Int,
    onSaveDxfClick: () -> Unit,
    onLoadDxfClick: () -> Unit,
    onScanClick: () -> Unit,
    onDeviceClick: (ScannedMeter) -> Unit,
    onDisconnectClick: () -> Unit,
    onDirectionSelected: (WallDirection) -> Unit,
    onDeleteWallClick: () -> Unit,
    onUndoClick: () -> Unit,
    onWallSelected: (Int?) -> Unit,
    onEditWallClick: () -> Unit,
    onCreateWall: (PointMm, Double, Long, Long, WallGrowthSide) -> Unit,
    onNoteSelected: (Int?) -> Unit,
    onNotePlaced: (String, PointMm) -> Unit,
    onNoteEdited: (Int, String) -> Unit,
    onEndpointMoveRequested: (Int, WallEndpoint, PointMm, PointMm) -> Unit,
    onMoveEndpoint: (PendingEndpointMove, Boolean) -> Unit,
    onDismissEndpointMove: () -> Unit,
    onStartPointSelected: (PointMm) -> Unit,
    onSaveWallProperties: (Int, Long, Long, WallGrowthSide) -> String,
    onNewLengthFieldFocusChanged: (Boolean) -> Unit,
    onLengthMeasurementConsumed: (Int) -> Unit,
    onDismissWallEditor: () -> Unit,
    onFingerDrawn: (PointMm, PointMm) -> Unit
) {
    val viewport = remember { PlanViewportState() }
    var snapOptions by remember { mutableStateOf(DraftSnapOptions()) }
    var showSnapControls by remember { mutableStateOf(false) }
    var showMeasurements by remember { mutableStateOf(false) }
    var showFullscreenPlan by remember { mutableStateOf(false) }
    var showReplaceDxfConfirmation by remember { mutableStateOf(false) }
    var showBleDialog by remember { mutableStateOf(false) }
    var overflowMenuExpanded by remember { mutableStateOf(false) }
    var pendingConnection by remember { mutableStateOf<PendingConnection?>(null) }
    var showNewWallSheet by remember { mutableStateOf(false) }
    var focusWallWidth by remember { mutableStateOf(false) }
    var showNoteDialog by remember { mutableStateOf(false) }
    var noteEditIndex by remember { mutableStateOf<Int?>(null) }
    var noteTextDraft by remember { mutableStateOf("") }
    var pendingNoteText by remember { mutableStateOf<String?>(null) }
    val density = LocalDensity.current
    val gridIntervalMm = with(density) {
        selectGridStepMm(24.dp.toPx(), viewport.pixelsPerMillimeter)
    }
    LaunchedEffect(dxfImportRevision) {
        if (dxfImportRevision > 0) viewport.fitToContent(wallSegments, notes)
    }

    val requestLoadDxf = {
        if (wallSegments.isEmpty()) onLoadDxfClick() else showReplaceDxfConfirmation = true
    }
    val openBlePicker = {
        overflowMenuExpanded = false
        showBleDialog = true
        onScanClick()
    }
    val editSelectedObject: () -> Unit = {
        val noteIndex = selectedNoteIndex
        if (noteIndex != null) {
            notes.getOrNull(noteIndex)?.let { note ->
                noteEditIndex = noteIndex
                noteTextDraft = note.text
                showNoteDialog = true
            }
        } else {
            focusWallWidth = false
            onEditWallClick()
        }
        Unit
    }
    val addNoteAction = {
        if (pendingNoteText != null) {
            pendingNoteText = null
        } else {
            noteEditIndex = null
            noteTextDraft = ""
            showNoteDialog = true
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 0.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = buildAnnotatedString {
                                withStyle(SpanStyle(color = DraftBeamColors.TextPrimary)) { append("draft") }
                                withStyle(
                                    SpanStyle(
                                        brush = Brush.linearGradient(
                                            listOf(DraftBeamColors.PrimaryLight, DraftBeamColors.Purple)
                                        )
                                    )
                                ) { append("Beam") }
                            },
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontSize = MaterialTheme.typography.titleMedium.fontSize * 1.3f
                            ),
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val connectionLabel = when {
                            selectedDeviceName != null -> "$selectedDeviceName conectado"
                            connectingDeviceName != null -> "Conectando a $connectingDeviceName…"
                            status.contains("falló", ignoreCase = true) ||
                                status.contains("error", ignoreCase = true) ||
                                status.contains("permiso", ignoreCase = true) -> status
                            else -> "Conectar BLE"
                        }
                        TextButton(
                            onClick = { if (selectedDeviceName == null && connectingDeviceName == null) openBlePicker() },
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                            enabled = selectedDeviceName == null && connectingDeviceName == null
                        ) {
                            val stateColor = when {
                                selectedDeviceName != null -> Color(0xFF28D995)
                                connectingDeviceName != null -> Color(0xFFE0AF68)
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Text("●", color = stateColor, fontSize = 13.sp)
                            Text(
                                text = "  $connectionLabel",
                                color = stateColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    IconButton(onClick = requestLoadDxf, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = "Abrir DXF")
                    }
                    IconButton(onClick = onSaveDxfClick, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Filled.Save, contentDescription = "Guardar DXF")
                    }
                    Box {
                        IconButton(
                            onClick = { overflowMenuExpanded = true },
                            modifier = Modifier.size(44.dp)
                        ) { Icon(Icons.Filled.MoreVert, contentDescription = "Más opciones BLE") }
                        DropdownMenu(
                            expanded = overflowMenuExpanded,
                            onDismissRequest = { overflowMenuExpanded = false },
                            containerColor = DraftBeamColors.PanelBackground
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (showMeasurements) "Ocultar mediciones (${notifications.size})"
                                        else "Mediciones (${notifications.size})"
                                    )
                                },
                                leadingIcon = { Icon(Icons.Filled.Straighten, contentDescription = null) },
                                onClick = {
                                    overflowMenuExpanded = false
                                    showMeasurements = !showMeasurements
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (selectedDeviceName == null && connectingDeviceName == null) "Conectar BLE"
                                        else "Cambiar medidor"
                                    )
                                },
                                leadingIcon = { Icon(Icons.Filled.Bluetooth, contentDescription = null) },
                                onClick = { openBlePicker() }
                            )
                            if (selectedDeviceName != null || connectingDeviceName != null) {
                                DropdownMenuItem(
                                    text = { Text("Desconectar") },
                                    onClick = {
                                        overflowMenuExpanded = false
                                        onDisconnectClick()
                                    }
                                )
                            }
                        }
                    }
            }

            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                PlanPreview(
                    segments = wallSegments,
                    notes = notes,
                    selectedSegmentIndex = selectedWallIndex,
                    selectedNoteIndex = selectedNoteIndex,
                    pendingNotePlacement = pendingNoteText != null,
                    snapOptions = snapOptions,
                    selectedStartPoint = selectedStartPoint,
                    selectedAngleDegrees = selectedAngleDegrees,
                    viewport = viewport,
                    onDirectionSelected = onDirectionSelected,
                    onSegmentSelected = onWallSelected,
                    onNoteSelected = onNoteSelected,
                    onNotePlaced = { position -> pendingNoteText?.let { text ->
                        onNotePlaced(text, position)
                        pendingNoteText = null
                    } },
                    onStartPointSelected = onStartPointSelected,
                    onFingerDrawn = onFingerDrawn,
                    onEndpointMoveRequested = onEndpointMoveRequested,
                    onConnectionRequested = { start, freeEnd, target ->
                        pendingConnection = PendingConnection(start, freeEnd, target)
                    },
                    modifier = Modifier.fillMaxSize()
                )
                pendingNoteText?.let {
                    Surface(
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 42.dp),
                        color = DraftBeamColors.PanelBackground.copy(alpha = 0.96f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 12.dp, end = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Tocá el plano para colocar la nota", style = MaterialTheme.typography.labelMedium)
                            TextButton(onClick = { pendingNoteText = null }) { Text("Cancelar") }
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 8.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Plano (${wallSegments.size} muros)",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Cuadrícula · ${formatScaleCoordinate(gridIntervalMm, gridIntervalMm)}",
                        color = DraftBeamColors.TextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                    TextButton(
                        onClick = { showFullscreenPlan = true },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                    ) {
                        Text("Ampliar", maxLines = 1)
                    }
                }
                SnapToolsOverlay(
                    expanded = showSnapControls,
                    snapOptions = snapOptions,
                    onSnapOptionsChange = { snapOptions = it },
                    onToggleSnapControls = { showSnapControls = !showSnapControls },
                    onFit = { viewport.fitToContent(wallSegments, notes) },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)
                )
            }

            if (showMeasurements) Surface(
                modifier = Modifier.fillMaxWidth(),
                color = DraftBeamColors.PanelBackground,
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            Icons.Filled.Bluetooth,
                            contentDescription = "Última medición Bluetooth",
                            tint = Color(0xFF7DCFFF),
                            modifier = Modifier.size(28.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = latestMeasurementMm?.let { formatLastMeasurement(it) } ?: "Sin mediciones",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = status.takeIf { latestMeasurementMm != null }
                                    ?: "Pulsa el medidor para capturar una distancia",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Column(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 116.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (notifications.isEmpty()) {
                            Text("Aún no se reciben notificaciones.", style = MaterialTheme.typography.bodySmall)
                        } else {
                            notifications.forEach { measurement ->
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    measurement.millimeters?.let { Text("Medida: $it mm") }
                                    measurement.parseError?.let { Text("No interpretada: $it", color = MaterialTheme.colorScheme.error) }
                                    Text("Bytes: ${measurement.rawHex}", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }

            PlanActionBar(
                hasWallSelection = selectedWallIndex != null,
                hasAnySelection = selectedWallIndex != null || selectedNoteIndex != null,
                canUndo = canUndo,
                onNewLine = { showNewWallSheet = true },
                onEdit = editSelectedObject,
                onDelete = onDeleteWallClick,
                onWidth = {
                    focusWallWidth = true
                    onEditWallClick()
                },
                onAddNote = addNoteAction,
                onUndo = onUndoClick
            )
        }
    }

    if (showBleDialog) {
        AlertDialog(
            onDismissRequest = { showBleDialog = false },
            title = { Text("Conectar medidor BLE") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (devices.isEmpty()) {
                        Text(
                            if (status.startsWith("Buscando", ignoreCase = true)) {
                                "Buscando medidores cercanos…"
                            } else {
                                "No se encontraron dispositivos. Comprueba que el medidor esté encendido y vuelve a buscar."
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        devices.forEach { meter ->
                            OutlinedButton(
                                onClick = {
                                    showBleDialog = false
                                    onDeviceClick(meter)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Filled.Bluetooth, contentDescription = null)
                                Text(meter.name, modifier = Modifier.padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onScanClick() }) { Text("Buscar de nuevo") }
            },
            dismissButton = {
                TextButton(onClick = { showBleDialog = false }) { Text("Cerrar") }
            }
        )
    }

    if (showReplaceDxfConfirmation) {
        AlertDialog(
            onDismissRequest = { showReplaceDxfConfirmation = false },
            title = { Text("Cargar otro plano") },
            text = { Text("El DXF reemplazará el plano actual. Podrás deshacer esta carga después.") },
            confirmButton = {
                TextButton(onClick = {
                    showReplaceDxfConfirmation = false
                    onLoadDxfClick()
                }) { Text("Continuar") }
            },
            dismissButton = {
                TextButton(onClick = { showReplaceDxfConfirmation = false }) { Text("Cancelar") }
            }
        )
    }

    if (showFullscreenPlan) {
        Dialog(
            onDismissRequest = { showFullscreenPlan = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Plano (${wallSegments.size} muros)")
                        TextButton(onClick = { showFullscreenPlan = false }) { Text("Volver") }
                    }
                    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        PlanPreview(
                            segments = wallSegments,
                            notes = notes,
                            selectedSegmentIndex = selectedWallIndex,
                            selectedNoteIndex = selectedNoteIndex,
                            pendingNotePlacement = pendingNoteText != null,
                            snapOptions = snapOptions,
                            selectedStartPoint = selectedStartPoint,
                            selectedAngleDegrees = selectedAngleDegrees,
                            viewport = viewport,
                            onDirectionSelected = onDirectionSelected,
                            onSegmentSelected = onWallSelected,
                            onNoteSelected = onNoteSelected,
                            onNotePlaced = { position -> pendingNoteText?.let { text ->
                                onNotePlaced(text, position)
                                pendingNoteText = null
                            } },
                            onStartPointSelected = onStartPointSelected,
                            onFingerDrawn = onFingerDrawn,
                            onEndpointMoveRequested = onEndpointMoveRequested,
                            onConnectionRequested = { start, freeEnd, target ->
                                pendingConnection = PendingConnection(start, freeEnd, target)
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                        SnapToolsOverlay(
                            expanded = showSnapControls,
                            snapOptions = snapOptions,
                            onSnapOptionsChange = { snapOptions = it },
                            onToggleSnapControls = { showSnapControls = !showSnapControls },
                            onFit = { viewport.fitToContent(wallSegments, notes) },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)
                        )
                    }
                    PlanActionBar(
                        hasWallSelection = selectedWallIndex != null,
                        hasAnySelection = selectedWallIndex != null || selectedNoteIndex != null,
                        canUndo = canUndo,
                        onNewLine = { showNewWallSheet = true },
                        onEdit = editSelectedObject,
                        onDelete = onDeleteWallClick,
                        onWidth = {
                            focusWallWidth = true
                            onEditWallClick()
                        },
                        onAddNote = addNoteAction,
                        onUndo = onUndoClick
                    )
                }
            }
        }
    }

    if (showNoteDialog) {
        val editingNote = noteEditIndex != null
        AlertDialog(
            onDismissRequest = {
                showNoteDialog = false
                noteEditIndex = null
            },
            title = { Text(if (editingNote) "Editar nota" else "Agregar nota") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = noteTextDraft,
                        onValueChange = { noteTextDraft = it.filter { character -> character != '\n' && character != '\r' }.take(255) },
                        label = { Text("Texto de la nota") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (!editingNote) {
                        Text("Después vas a tocar el plano para elegir dónde colocarla.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = noteTextDraft.isNotBlank(),
                    onClick = {
                        val text = noteTextDraft.trim()
                        val index = noteEditIndex
                        if (index == null) pendingNoteText = text else onNoteEdited(index, text)
                        showNoteDialog = false
                        noteEditIndex = null
                    }
                ) { Text(if (editingNote) "Guardar" else "Continuar") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showNoteDialog = false
                    noteEditIndex = null
                }) { Text("Cancelar") }
            }
        )
    }

    pendingEndpointMove?.let { move ->
        val sharedCount = PlanGeometry.endpointUseCount(wallSegments, move.origin)
        AlertDialog(
            onDismissRequest = onDismissEndpointMove,
            title = { Text("Mover unión") },
            text = { Text("Este extremo está conectado a $sharedCount tramos. ¿Quieres mover solo este muro o toda la unión?") },
            confirmButton = {
                TextButton(onClick = { onMoveEndpoint(move, true) }) {
                    Text("Toda la unión")
                }
            },
            dismissButton = {
                TextButton(onClick = { onMoveEndpoint(move, false) }) {
                    Text("Solo este muro")
                }
            }
        )
    }

    val editingSegment = selectedWallIndex?.takeIf { wallEditorOpen }?.let(wallSegments::getOrNull)
    if (wallEditorOpen && selectedWallIndex != null && editingSegment != null) {
        WallEditorSheet(
            segment = editingSegment,
            index = selectedWallIndex,
            focusWidth = focusWallWidth,
            lengthMeasurementEvent = lengthMeasurementEvent,
            onNewLengthFieldFocusChanged = onNewLengthFieldFocusChanged,
            onLengthMeasurementConsumed = onLengthMeasurementConsumed,
            onDismiss = onDismissWallEditor,
            onSaveWall = { lengthMm, widthMm, side ->
                onSaveWallProperties(selectedWallIndex, lengthMm, widthMm, side)
                onDismissWallEditor()
            },
            onDelete = onDeleteWallClick
        )
    }

    if (showNewWallSheet) {
        val startPoint = selectedStartPoint ?: wallSegments.lastOrNull()?.end ?: PointMm(0.0, 0.0)
        NewWallSheet(
            startPoint = startPoint,
            angleDegrees = selectedAngleDegrees,
            onDismiss = { showNewWallSheet = false },
            onCreateWall = { length, width, side ->
                onCreateWall(startPoint, selectedAngleDegrees, length, width, side)
                showNewWallSheet = false
            }
        )
    }

    pendingConnection?.let { connection ->
        AlertDialog(
            onDismissRequest = {
                onFingerDrawn(connection.start, connection.freeEnd)
                pendingConnection = null
            },
            title = { Text("Conectar extremos") },
            text = { Text("El extremo está cerca de un punto existente. ¿Quieres conectar la línea con ese punto?") },
            confirmButton = {
                TextButton(onClick = {
                    onFingerDrawn(connection.start, connection.target)
                    pendingConnection = null
                }) { Text("Sí") }
            },
            dismissButton = {
                TextButton(onClick = {
                    onFingerDrawn(connection.start, connection.freeEnd)
                    pendingConnection = null
                }) { Text("No") }
            }
        )
    }
}

@Composable
private fun OverlayIconButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.96f else 0.65f),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
    ) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(52.dp)) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun PlanActionBar(
    hasWallSelection: Boolean,
    hasAnySelection: Boolean,
    canUndo: Boolean,
    onNewLine: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onWidth: () -> Unit,
    onAddNote: () -> Unit,
    onUndo: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = DraftBeamColors.PanelBackground,
        shape = RoundedCornerShape(22.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BottomAction(Icons.Filled.Add, "Nueva línea", true, onNewLine, Modifier.weight(1f))
            BottomAction(Icons.Filled.Edit, "Editar", hasAnySelection, onEdit, Modifier.weight(1f))
            BottomAction(Icons.Filled.Delete, "Eliminar", hasAnySelection, onDelete, Modifier.weight(1f))
            BottomAction(Icons.Filled.Straighten, "Ancho", hasWallSelection, onWidth, Modifier.weight(1f))
            BottomAction(Icons.AutoMirrored.Filled.NoteAdd, "Agregar nota", true, onAddNote, Modifier.weight(1f))
            BottomAction(Icons.AutoMirrored.Filled.Undo, "Deshacer", canUndo, onUndo, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SnapToolsOverlay(
    expanded: Boolean,
    snapOptions: DraftSnapOptions,
    onSnapOptionsChange: (DraftSnapOptions) -> Unit,
    onToggleSnapControls: () -> Unit,
    onFit: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        if (expanded) {
            SnapControlPanel(
                options = snapOptions,
                onOptionsChange = onSnapOptionsChange,
                modifier = Modifier.width(260.dp)
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
            OverlayIconButton(
                icon = Icons.Filled.CenterFocusStrong,
                label = "Ajustar plano",
                enabled = true,
                onClick = onFit
            )
            OverlayIconButton(
                icon = Icons.Filled.Layers,
                label = "Capas (próximamente)",
                enabled = false,
                onClick = {}
            )
            OutlinedButton(
                onClick = onToggleSnapControls,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, DraftBeamColors.TechnicalBlue),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = DraftBeamColors.PanelBackground.copy(alpha = 0.96f),
                    contentColor = DraftBeamColors.LaserCyan
                )
            ) {
                Icon(Icons.Filled.Straighten, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (expanded) "Ocultar" else "Capturas", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun BottomAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.width(64.dp)
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(23.dp))
        }
        Text(
            text = label,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
    }
}

private fun formatLastMeasurement(millimeters: Long): String {
    val meters = millimeters / 1_000.0
    return String.format(java.util.Locale.ROOT, "%.3f m → %d mm", meters, millimeters)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WallEditorSheet(
    segment: WallSegment,
    index: Int,
    focusWidth: Boolean,
    lengthMeasurementEvent: LengthMeasurement?,
    onNewLengthFieldFocusChanged: (Boolean) -> Unit,
    onLengthMeasurementConsumed: (Int) -> Unit,
    onDismiss: () -> Unit,
    onSaveWall: (Long, Long, WallGrowthSide) -> Unit,
    onDelete: () -> Unit
) {
    var lengthText by remember(index, segment.lengthMm) { mutableStateOf("") }
    var widthText by remember(index, segment.widthMm) { mutableStateOf("") }
    var growthSide by remember(index, segment.growthSide) { mutableStateOf(segment.growthSide) }
    var validationError by remember(index) { mutableStateOf<String?>(null) }
    val widthFocusRequester = remember { FocusRequester() }

    LaunchedEffect(focusWidth) {
        if (focusWidth) widthFocusRequester.requestFocus()
    }
    LaunchedEffect(lengthMeasurementEvent?.id) {
        lengthMeasurementEvent?.let { event ->
            onLengthMeasurementConsumed(event.id)
            onSaveWall(event.millimeters, segment.widthMm, segment.growthSide)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = DraftBeamColors.Surface,
        contentColor = DraftBeamColors.TextPrimary,
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = DraftBeamColors.TextMuted)
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Muro seleccionado", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "Muro ${index + 1} · ${segment.lengthMm} mm",
                    style = MaterialTheme.typography.bodySmall,
                    color = DraftBeamColors.TextSecondary
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WallEditorField(
                    label = "Largo (mm)",
                    value = lengthText,
                    placeholder = segment.lengthMm.toString(),
                    supportingText = "Actual: ${segment.lengthMm} mm",
                    modifier = Modifier.weight(1f),
                    onValueChange = { input ->
                        lengthText = input.filter(Char::isDigit)
                        validationError = null
                    },
                    onFocusChanged = onNewLengthFieldFocusChanged
                )
                WallEditorField(
                    label = "Ancho muro (mm)",
                    value = widthText,
                    placeholder = segment.widthMm.toString(),
                    supportingText = "Actual: ${segment.widthMm} mm",
                    modifier = Modifier.weight(1f),
                    focusRequester = widthFocusRequester.takeIf { focusWidth },
                    onValueChange = { input ->
                        widthText = input.filter(Char::isDigit)
                        validationError = null
                    }
                )
                WallEditorField(
                    label = "Ángulo",
                    value = "${segment.angleDegrees.formatAngle()}°",
                    supportingText = wallOrientationLabel(segment.angleDegrees),
                    modifier = Modifier.weight(0.82f),
                    readOnly = true
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Dirección del muro", style = MaterialTheme.typography.labelLarge, color = DraftBeamColors.TextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WallGrowthSide.orderedForAngle(segment.angleDegrees).forEach { side ->
                        val directionLabel = wallGrowthDirectionLabel(side, segment.angleDegrees)
                        Button(
                            onClick = { growthSide = side },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            shape = RoundedCornerShape(14.dp),
                            border = if (growthSide == side) null else BorderStroke(1.dp, DraftBeamColors.Border),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (growthSide == side) DraftBeamColors.Primary else DraftBeamColors.SurfaceElevated,
                                contentColor = DraftBeamColors.TextPrimary
                            ),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                        ) {
                            Text(directionLabel, maxLines = 1, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            validationError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onDelete,
                    modifier = Modifier.weight(1.2f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DraftBeamColors.Error.copy(alpha = 0.14f),
                        contentColor = DraftBeamColors.Error
                    ),
                    border = BorderStroke(1.dp, DraftBeamColors.Error.copy(alpha = 0.35f)),
                    contentPadding = PaddingValues(horizontal = 6.dp)
                ) { Text("Eliminar muro", maxLines = 1, style = MaterialTheme.typography.labelMedium) }
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, DraftBeamColors.Border),
                    contentPadding = PaddingValues(horizontal = 6.dp)
                ) { Text("Cancelar", maxLines = 1, style = MaterialTheme.typography.labelMedium) }
                Button(
                    onClick = {
                        val length = if (lengthText.isBlank()) segment.lengthMm else lengthText.toLongOrNull()
                        val width = if (widthText.isBlank()) segment.widthMm else widthText.toLongOrNull()
                        if (length == null || length <= 0) {
                            validationError = "Ingresa una longitud mayor que cero."
                        } else if (width == null || width < 0) {
                            validationError = "Ingresa un ancho de cero o más milímetros."
                        } else {
                            onSaveWall(length, width, growthSide)
                        }
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DraftBeamColors.Primary,
                        contentColor = DraftBeamColors.TextPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 6.dp)
                ) { Text("Guardar", maxLines = 1, style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewWallSheet(
    startPoint: PointMm,
    angleDegrees: Double,
    onDismiss: () -> Unit,
    onCreateWall: (Long, Long, WallGrowthSide) -> Unit
) {
    var lengthText by remember { mutableStateOf("") }
    var widthText by remember { mutableStateOf("") }
    var growthSide by remember(angleDegrees) { mutableStateOf(PlanGeometry.defaultGrowthSide(angleDegrees)) }
    var validationError by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = DraftBeamColors.Surface,
        contentColor = DraftBeamColors.TextPrimary,
        dragHandle = { BottomSheetDefaults.DragHandle(color = DraftBeamColors.TextMuted) }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Nueva línea", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "Inicio ${startPoint.formatCoordinates()} mm · ${wallOrientationLabel(angleDegrees)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = DraftBeamColors.TextSecondary
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WallEditorField(
                    label = "Largo (mm)",
                    value = lengthText,
                    supportingText = "Ingresa la longitud",
                    modifier = Modifier.weight(1f),
                    onValueChange = { input ->
                        lengthText = input.filter(Char::isDigit)
                        validationError = null
                    }
                )
                WallEditorField(
                    label = "Ancho muro (mm)",
                    value = widthText,
                    placeholder = "0",
                    supportingText = "0 = línea sin ancho",
                    modifier = Modifier.weight(1f),
                    onValueChange = { input ->
                        widthText = input.filter(Char::isDigit)
                        validationError = null
                    }
                )
                WallEditorField(
                    label = "Dirección",
                    value = wallOrientationLabel(angleDegrees),
                    supportingText = "Ángulo ${angleDegrees.formatAngle()}°",
                    modifier = Modifier.weight(1f),
                    readOnly = true
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Lado de crecimiento del muro", style = MaterialTheme.typography.labelLarge, color = DraftBeamColors.TextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WallGrowthSide.orderedForAngle(angleDegrees).forEach { side ->
                        Button(
                            onClick = { growthSide = side },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            shape = RoundedCornerShape(14.dp),
                            border = if (growthSide == side) null else BorderStroke(1.dp, DraftBeamColors.Border),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (growthSide == side) DraftBeamColors.Primary else DraftBeamColors.SurfaceElevated,
                                contentColor = DraftBeamColors.TextPrimary
                            ),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                        ) {
                            Text(wallGrowthDirectionLabel(side, angleDegrees), maxLines = 1, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            validationError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text("Cancelar")
                }
                Button(
                    onClick = {
                        val length = lengthText.toLongOrNull()
                        val width = widthText.toLongOrNull() ?: if (widthText.isBlank()) 0L else null
                        when {
                            length == null || length <= 0 -> validationError = "Ingresa una longitud mayor que cero."
                            width == null || width < 0 -> validationError = "Ingresa un ancho de cero o más milímetros."
                            else -> onCreateWall(length, width, growthSide)
                        }
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DraftBeamColors.Primary)
                ) { Text("Crear línea") }
            }
        }
    }
}

@Composable
private fun WallEditorField(
    label: String,
    value: String,
    placeholder: String? = null,
    supportingText: String,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    onValueChange: (String) -> Unit = {},
    onFocusChanged: (Boolean) -> Unit = {},
    focusRequester: FocusRequester? = null
) {
    Surface(
        modifier = modifier.heightIn(min = 92.dp),
        shape = RoundedCornerShape(12.dp),
        color = DraftBeamColors.SurfaceElevated,
        border = BorderStroke(1.dp, DraftBeamColors.Border)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = DraftBeamColors.TextSecondary, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth()
                    .then(focusRequester?.let(Modifier::focusRequester) ?: Modifier)
                    .onFocusChanged { onFocusChanged(it.isFocused) },
                readOnly = readOnly,
                singleLine = true,
                textStyle = TextStyle(
                    color = DraftBeamColors.TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            ) { innerTextField ->
                Box {
                    if (value.isEmpty() && placeholder != null) {
                        Text(placeholder, color = DraftBeamColors.TextMuted, fontSize = 17.sp)
                    }
                    innerTextField()
                }
            }
            Text(
                supportingText,
                style = MaterialTheme.typography.labelSmall,
                color = DraftBeamColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun wallOrientationLabel(angleDegrees: Double): String {
    val angle = PlanGeometry.normalizeAngle(angleDegrees)
    return when (angle) {
        0.0 -> "Horizontal (X+)"
        90.0 -> "Vertical (Y+)"
        180.0 -> "Horizontal (X−)"
        270.0 -> "Vertical (Y−)"
        else -> "Diagonal"
    }
}

private fun wallGrowthDirectionLabel(side: WallGrowthSide, angleDegrees: Double): String =
    when (side.axisLabel(angleDegrees)) {
        "Arriba" -> "Arriba (Y+)"
        "Abajo" -> "Abajo (Y−)"
        "Izquierda" -> "Izquierda (X−)"
        "Derecha" -> "Derecha (X+)"
        else -> side.axisLabel(angleDegrees)
    }

data class ScannedMeter(val device: BluetoothDevice, val name: String)

fun Double.formatAngle(): String = String.format(java.util.Locale.ROOT, "%.1f", this)

private fun PointMm.formatCoordinates(): String =
    "(${String.format(java.util.Locale.ROOT, "%.0f", x)}, ${String.format(java.util.Locale.ROOT, "%.0f", y)})"

private fun angularDistance(first: Double, second: Double): Double {
    val difference = kotlin.math.abs(PlanGeometry.normalizeAngle(first) - PlanGeometry.normalizeAngle(second))
    return minOf(difference, 360.0 - difference)
}

private val METER_SERVICE_UUID: UUID = UUID.fromString("0000ffb0-0000-1000-8000-00805f9b34fb")
private val MEASUREMENT_CHARACTERISTIC_UUID: UUID =
    UUID.fromString("0000ffb2-0000-1000-8000-00805f9b34fb")
private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
private const val SCAN_DURATION_MS = 12_000L
private const val MAX_DXF_IMPORT_CHARS = 16 * 1024 * 1024
private const val DXF_PREFERENCES = "dxf_document_preferences"
private const val DXF_URI_KEY = "associated_dxf_uri"
