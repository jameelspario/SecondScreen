package com.example.secondscreenandroid.connection

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class DiscoveredHost(val name: String, val host: String, val port: Int)

sealed class ConnectionState {
    object Idle : ConnectionState()
    data class Discovering(val hosts: List<DiscoveredHost>) : ConnectionState()
    data class Connecting(val host: String, val port: Int) : ConnectionState()
    object Connected : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

class ConnectionViewModel : ViewModel() {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state

    private val _discoveredHosts = MutableStateFlow<List<DiscoveredHost>>(emptyList())
    val discoveredHosts: StateFlow<List<DiscoveredHost>> = _discoveredHosts

    private var streamSocket: StreamSocket? = null
    private var connectJob: Job? = null
    private var discoveryService: DiscoveryService? = null

    var onFrameReceived: ((ByteArray) -> Unit)? = null

    fun startDiscovery(context: Context) {
        _discoveredHosts.value = emptyList()
        _state.value = ConnectionState.Discovering(emptyList())
        discoveryService?.stopDiscovery()
        discoveryService = DiscoveryService(context).also { svc ->
            svc.startDiscovery(
                onFound = { host, port, name ->
                    val h = DiscoveredHost(name, host, port)
                    val current = _discoveredHosts.value.toMutableList()
                    if (current.none { it.name == name }) {
                        current.add(h)
                        _discoveredHosts.value = current
                    }
                    _state.value = ConnectionState.Discovering(_discoveredHosts.value)
                },
                onLost = { name ->
                    _discoveredHosts.value = _discoveredHosts.value.filter { it.name != name }
                    _state.value = ConnectionState.Discovering(_discoveredHosts.value)
                }
            )
        }
    }

    fun connectToHost(host: String, port: Int = 7070) {
        connectJob?.cancel()
        _state.value = ConnectionState.Connecting(host, port)
        val socket = StreamSocket(host, port)
        streamSocket = socket
        connectJob = viewModelScope.launch {
            socket.connect(
                onFrame = { data -> onFrameReceived?.invoke(data) },
                onConnected = { _state.value = ConnectionState.Connected },
                onDisconnected = { error -> _state.value = ConnectionState.Error(error) }
            )
        }
    }

    fun connectUSB() = connectToHost("127.0.0.1", 7070)

    fun sendTouch(type: Byte, x: Float, y: Float) {
        streamSocket?.sendTouchEvent(type, x, y)
    }

    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        streamSocket?.disconnect()
        streamSocket = null
        discoveryService?.stopDiscovery()
        _state.value = ConnectionState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }
}
