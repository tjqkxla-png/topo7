package kr.koseco.gandansurvey.v90.gnss

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

data class BtDeviceItem(
    val name: String,
    val address: String,
    val isHiTargetV90: Boolean,
    val isBonded: Boolean = true
)

/**
 * Classic Bluetooth SPP (UUID 00001101-0000-1000-8000-00805F9B34FB) Manager
 * Optimized specifically for Hi-Target V90 / V90 Plus / V30 / V60 / iRTK receivers.
 *
 * Key V90 Fixes:
 * 1. Upon connection, sends V90 NMEA 1Hz activation commands so receivers that default to
 *    binary/silent mode immediately start streaming GGA, GSV, GSA, GST, and RMC sentences.
 * 2. Uses a raw byte scanner that extracts ASCII '$'..'\\n' NMEA frames even when V90
 *    interleaves binary RTCM/HD-protocol bytes on the same SPP channel.
 * 3. Supports live Bluetooth discovery scan in addition to paired devices.
 */
@SuppressLint("MissingPermission")
class GnssBluetoothManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onNmeaLine: (String) -> Unit,
    private val onConnectionStateChanged: (Boolean, String) -> Unit
) {
    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var readJob: Job? = null

    private val _discoveredDevices = MutableStateFlow<List<BtDeviceItem>>(emptyList())
    val discoveredDevices: StateFlow<List<BtDeviceItem>> = _discoveredDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _nmeaLinesReceived = MutableStateFlow(0L)
    val nmeaLinesReceived: StateFlow<Long> = _nmeaLinesReceived.asStateFlow()

    private val _rawBytesReceived = MutableStateFlow(0L)
    val rawBytesReceived: StateFlow<Long> = _rawBytesReceived.asStateFlow()

    private val _recentRawLog = MutableStateFlow<List<String>>(emptyList())
    val recentRawLog: StateFlow<List<String>> = _recentRawLog.asStateFlow()

    private var receiverRegistered = false

    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (device != null) {
                        addOrUpdateDevice(device, isBonded = device.bondState == BluetoothDevice.BOND_BONDED)
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    _isScanning.value = false
                }
            }
        }
    }

    fun getPairedDevices(): List<BtDeviceItem> {
        val bonded = try {
            adapter?.bondedDevices?.map { dev ->
                val devName = dev.name ?: "알 수 없는 블루투스 기기"
                val isV90 = isLikelyGnssReceiver(devName)
                BtDeviceItem(
                    name = devName,
                    address = dev.address,
                    isHiTargetV90 = isV90,
                    isBonded = true
                )
            }?.sortedByDescending { it.isHiTargetV90 } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        _discoveredDevices.value = bonded
        return bonded
    }

    fun startDiscoveryScan() {
        getPairedDevices()
        val btAdapter = adapter ?: return
        try {
            if (!receiverRegistered) {
                val filter = IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_FOUND)
                    addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                }
                context.registerReceiver(discoveryReceiver, filter)
                receiverRegistered = true
            }
            if (btAdapter.isDiscovering) {
                btAdapter.cancelDiscovery()
            }
            _isScanning.value = true
            btAdapter.startDiscovery()
        } catch (_: Exception) {
            _isScanning.value = false
        }
    }

    fun stopDiscoveryScan() {
        try {
            if (adapter?.isDiscovering == true) {
                adapter.cancelDiscovery()
            }
        } catch (_: Exception) {
        }
        _isScanning.value = false
    }

    private fun addOrUpdateDevice(dev: BluetoothDevice, isBonded: Boolean) {
        val devName = try {
            dev.name ?: "블루투스 기기 (${dev.address.takeLast(5)})"
        } catch (_: Exception) {
            "블루투스 기기"
        }
        val item = BtDeviceItem(
            name = devName,
            address = dev.address,
            isHiTargetV90 = isLikelyGnssReceiver(devName),
            isBonded = isBonded
        )
        val current = _discoveredDevices.value.toMutableList()
        val existingIdx = current.indexOfFirst { it.address == item.address }
        if (existingIdx >= 0) {
            current[existingIdx] = item
        } else {
            current.add(item)
        }
        _discoveredDevices.value = current.sortedWith(
            compareByDescending<BtDeviceItem> { it.isHiTargetV90 }.thenByDescending { it.isBonded }
        )
    }

    private fun isLikelyGnssReceiver(name: String): Boolean {
        val upper = name.uppercase()
        val digitsCount = name.count { it.isDigit() }
        return upper.contains("V90") ||
            upper.contains("V30") ||
            upper.contains("V60") ||
            upper.contains("V200") ||
            upper.contains("HITARGET") ||
            upper.contains("HI-TARGET") ||
            upper.contains("IRTK") ||
            upper.contains("GNSS") ||
            upper.contains("RTK") ||
            digitsCount >= 6 // Hi-Target serial number format e.g. 11606199
    }

    fun connect(address: String, deviceName: String) {
        disconnect()
        stopDiscoveryScan()
        readJob = scope.launch(Dispatchers.IO) {
            try {
                val device: BluetoothDevice = adapter?.getRemoteDevice(address)
                    ?: throw IllegalStateException("블루투스 어댑터를 찾을 수 없습니다.")

                onConnectionStateChanged(false, "$deviceName 연결 시도 중...")

                val btSocket = createBestSocket(device)
                socket = btSocket
                inputStream = btSocket.inputStream
                outputStream = btSocket.outputStream

                _nmeaLinesReceived.value = 0L
                _rawBytesReceived.value = 0L
                appendLog("[연결 성공] $deviceName ($address)")

                onConnectionStateChanged(true, "$deviceName 연결됨")

                // Send Hi-Target V90 / Trimble / NovAtel / Unicore NMEA activation commands
                sendV90NmeaActivationCommands()

                readByteStreamLoop(deviceName)
            } catch (e: Exception) {
                appendLog("[연결 오류] ${e.message ?: "알 수 없는 오류"}")
                onConnectionStateChanged(false, "연결 실패: ${e.message ?: "기기 전원을 확인하세요"}")
                disconnectInternal()
            }
        }
    }

    private fun createBestSocket(device: BluetoothDevice): BluetoothSocket {
        // Attempt 1: Standard secure RFCOMM SPP
        try {
            val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
            s.connect()
            return s
        } catch (_: Exception) {
        }
        // Attempt 2: Insecure RFCOMM SPP (preferred by many Hi-Target V90 Bluetooth modules)
        try {
            val s = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
            s.connect()
            return s
        } catch (_: Exception) {
        }
        // Attempt 3: Reflection fallback on channel 1
        val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
        val fallbackSocket = m.invoke(device, 1) as BluetoothSocket
        fallbackSocket.connect()
        return fallbackSocket
    }

    /**
     * Hi-Target V90 internal OEM boards (Trimble BD970/BD990, NovAtel OEM6/7, Unicore UB4B0)
     * sometimes boot with NMEA output disabled on the Bluetooth port until requested.
     * Sending these standard ASCII commands wakes up 1Hz NMEA output (GGA, GSV, GSA, GST, RMC).
     */
    fun sendV90NmeaActivationCommands() {
        scope.launch(Dispatchers.IO) {
            val commands = listOf(
                "\r\n",
                "\$JASC,GPGGA,1\r\n",
                "\$JASC,GPGSV,1\r\n",
                "\$JASC,GPGSA,1\r\n",
                "\$JASC,GPGST,1\r\n",
                "\$JASC,GPRMC,1\r\n",
                "LOG GPGGA ONTIME 1\r\n",
                "LOG GPGSV ONTIME 1\r\n",
                "LOG GPGSA ONTIME 1\r\n",
                "LOG GPGST ONTIME 1\r\n",
                "LOG GPRMC ONTIME 1\r\n",
                "SET,NMEA.GGA,1\r\n",
                "SET,NMEA.GSV,1\r\n",
                "SET,NMEA.GSA,1\r\n",
                "SET,NMEA.GST,1\r\n",
                "SET,NMEA.RMC,1\r\n"
            )
            try {
                val out = outputStream ?: return@launch
                for (cmd in commands) {
                    out.write(cmd.toByteArray(StandardCharsets.US_ASCII))
                    out.flush()
                    delay(35)
                }
                appendLog("[초기화] V90 NMEA 1Hz 출력 활성화 명령 전송 완료")
            } catch (e: Exception) {
                appendLog("[초기화 경고] NMEA 요청 전송 중 예외: ${e.message}")
            }
        }
    }

    /**
     * Raw byte scanner that extracts every NMEA sentence starting with '$' (0x24) up to CR/LF,
     * completely immune to binary headers or proprietary framing bytes before '$'.
     */
    private suspend fun readByteStreamLoop(deviceName: String) {
        val stream = inputStream ?: return
        val readBuf = ByteArray(2048)
        val sentenceBuf = ByteArray(512)
        var sentenceLen = 0
        var insideNmea = false
        var lastCommandRetryTime = System.currentTimeMillis()

        while (scope.isActive && socket?.isConnected == true) {
            val readCount = stream.read(readBuf)
            if (readCount <= 0) {
                delay(20)
                continue
            }
            _rawBytesReceived.value += readCount

            for (i in 0 until readCount) {
                val b = readBuf[i].toInt() and 0xFF
                if (b == 0x24) { // '$' start of NMEA sentence
                    if (insideNmea && sentenceLen >= 6) {
                        emitExtractedNmea(sentenceBuf, sentenceLen)
                    }
                    sentenceLen = 0
                    insideNmea = true
                    sentenceBuf[sentenceLen++] = b.toByte()
                } else if (insideNmea) {
                    if (b == 0x0D || b == 0x0A) { // CR or LF
                        if (sentenceLen >= 6) {
                            emitExtractedNmea(sentenceBuf, sentenceLen)
                        }
                        sentenceLen = 0
                        insideNmea = false
                    } else if (b in 0x20..0x7E && sentenceLen < sentenceBuf.size - 1) {
                        sentenceBuf[sentenceLen++] = b.toByte()
                    } else if (sentenceLen >= sentenceBuf.size - 1) {
                        // Overflow guard
                        sentenceLen = 0
                        insideNmea = false
                    }
                }
            }

            // If bytes are coming in (or 8 seconds elapsed with 0 NMEA lines), re-send activation once
            val now = System.currentTimeMillis()
            if (_nmeaLinesReceived.value == 0L && now - lastCommandRetryTime > 8000L) {
                lastCommandRetryTime = now
                sendV90NmeaActivationCommands()
            }
        }
        onConnectionStateChanged(false, "$deviceName 연결이 종료되었습니다.")
    }

    private fun emitExtractedNmea(buf: ByteArray, length: Int) {
        val sentence = String(buf, 0, length, StandardCharsets.US_ASCII).trim()
        if (sentence.startsWith("$") && sentence.length >= 6) {
            _nmeaLinesReceived.value += 1
            appendLog(sentence)
            onNmeaLine(sentence)
        }
    }

    private fun appendLog(line: String) {
        val current = _recentRawLog.value
        val updated = if (current.size >= 40) {
            current.drop(current.size - 39) + line
        } else {
            current + line
        }
        _recentRawLog.value = updated
    }

    /**
     * Writes RTCM v3.x differential correction bytes received from NTRIP caster (VRS)
     * directly to the Hi-Target V90 Bluetooth SPP output stream.
     */
    fun writeRtcmBytes(bytes: ByteArray, length: Int = bytes.size): Boolean {
        return try {
            val out = outputStream ?: return false
            out.write(bytes, 0, length)
            out.flush()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun disconnect() {
        readJob?.cancel()
        readJob = null
        disconnectInternal()
    }

    private fun disconnectInternal() {
        try {
            inputStream?.close()
        } catch (_: Exception) {}
        try {
            outputStream?.close()
        } catch (_: Exception) {}
        try {
            socket?.close()
        } catch (_: Exception) {}
        inputStream = null
        outputStream = null
        socket = null
    }
}
