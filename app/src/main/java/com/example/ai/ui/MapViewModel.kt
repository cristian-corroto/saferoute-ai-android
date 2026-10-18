package com.example.ai.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.data.ApiClient
import com.example.ai.data.EstadoPrediccion
import com.example.ai.data.PrediccionRequest
import com.example.ai.data.PrediccionResponse
import com.example.ai.data.Zona
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Calendar

class MapViewModel : ViewModel() {

    private val _estado = MutableStateFlow<EstadoPrediccion>(EstadoPrediccion.Idle)
    val estado: StateFlow<EstadoPrediccion> = _estado

    private val _zonas = MutableStateFlow<List<Zona>>(emptyList())
    val zonas: StateFlow<List<Zona>> = _zonas

    private val _prediccionesZonas = MutableStateFlow<Map<String, PrediccionResponse>>(emptyMap())
    val prediccionesZonas: StateFlow<Map<String, PrediccionResponse>> = _prediccionesZonas

    private val _hora = MutableStateFlow(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
    val hora: StateFlow<Int> = _hora

    private val _diaSemana = MutableStateFlow(
        (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 2).coerceIn(0, 6)
    )
    val diaSemana: StateFlow<Int> = _diaSemana

    private val _densidad = MutableStateFlow(50)
    val densidad: StateFlow<Int> = _densidad

    private val _zonaBuscada = MutableStateFlow<Zona?>(null)
    val zonaBuscada: StateFlow<Zona?> = _zonaBuscada

    private val _busquedaActiva = MutableStateFlow(false)
    val busquedaActiva: StateFlow<Boolean> = _busquedaActiva

    init {
        cargarZonasEnBackground()
    }

    private fun cargarZonasEnBackground() {
        viewModelScope.launch {
            try {
                val respuesta = ApiClient.service.obtenerZonas()
                _zonas.value = respuesta.zonas
            } catch (_: Exception) {
                _zonas.value = zonasLocales()
            }
        }
    }

    fun buscarYPredecir(nombre: String, latitud: Double, longitud: Double) {
        _densidad.value = calcularDensidad()

        val zonaConocida = _zonas.value.minByOrNull { zona ->
            val dLat = zona.latitud - latitud
            val dLon = zona.longitud - longitud
            dLat * dLat + dLon * dLon
        }?.let { zona ->
            val distancia = distanciaKm(latitud, longitud, zona.latitud, zona.longitud)
            if (distancia < 5.0) zona else null
        }

        val zonaActiva = zonaConocida ?: Zona(
            nombre = nombre,
            latitud = latitud,
            longitud = longitud,
            radioKm = 0.8,
            ocioNocturno = false
        )

        _zonaBuscada.value = zonaActiva
        _busquedaActiva.value = true
        predecirRiesgo(zonaActiva.latitud, zonaActiva.longitud)
    }

    fun limpiarBusqueda() {
        _zonaBuscada.value = null
        _busquedaActiva.value = false
        _estado.value = EstadoPrediccion.Idle
    }

    private fun calcularDensidad(): Int {
        val hora = _hora.value
        val dia = _diaSemana.value

        val base = when {
            hora in 0..5 -> 10
            hora in 6..8 -> 25
            hora in 9..13 -> 65
            hora in 14..16 -> 55
            hora in 17..19 -> 70
            hora in 20..21 -> 60
            hora == 22 -> 50
            hora == 23 -> 40
            else -> 30
        }

        val ajuste = when {
            dia == 5 && hora in 22..23 -> 20
            dia == 5 && hora in 0..3 -> 15
            dia == 4 && hora in 22..23 -> 15
            dia == 6 -> -10
            dia in 0..3 && hora in 9..17 -> 5
            else -> 0
        }

        return (base + ajuste).coerceIn(5, 95)
    }

    fun predecirTodasZonas() {
        _densidad.value = calcularDensidad()
        val zonas = _zonas.value
        if (zonas.isEmpty()) return

        viewModelScope.launch {
            val resultado = mutableMapOf<String, PrediccionResponse>()
            zonas.forEach { zona ->
                try {
                    val request = PrediccionRequest(
                        latitud = zona.latitud,
                        longitud = zona.longitud,
                        hora = _hora.value,
                        diaSemana = _diaSemana.value,
                        densidad = _densidad.value
                    )
                    val response = ApiClient.service.predecirRiesgo(request)
                    resultado[zona.nombre] = response
                } catch (_: Exception) {
                }
            }
            _prediccionesZonas.value = resultado
        }
    }

    fun predecirRiesgo(latitud: Double, longitud: Double) {
        _estado.value = EstadoPrediccion.Cargando
        viewModelScope.launch {
            try {
                val request = PrediccionRequest(
                    latitud = latitud,
                    longitud = longitud,
                    hora = _hora.value,
                    diaSemana = _diaSemana.value,
                    densidad = _densidad.value
                )
                val respuesta = ApiClient.service.predecirRiesgo(request)
                _estado.value = EstadoPrediccion.Exito(respuesta)
            } catch (e: Exception) {
                _estado.value = EstadoPrediccion.Error("No se pudo conectar con la API.\n${e.message}")
            }
        }
    }

    fun setHora(hora: Int) {
        _hora.value = hora
        _zonaBuscada.value?.let { predecirRiesgo(it.latitud, it.longitud) }
    }

    fun setDiaSemana(dia: Int) {
        _diaSemana.value = dia
        _zonaBuscada.value?.let { predecirRiesgo(it.latitud, it.longitud) }
    }

    fun setDensidad(densidad: Int) {
        _densidad.value = densidad
        _zonaBuscada.value?.let { predecirRiesgo(it.latitud, it.longitud) }
    }

    fun usarHoraActual() {
        val cal = Calendar.getInstance()
        _hora.value = cal.get(Calendar.HOUR_OF_DAY)
        _diaSemana.value = (cal.get(Calendar.DAY_OF_WEEK) - 2).coerceIn(0, 6)
        _zonaBuscada.value?.let { predecirRiesgo(it.latitud, it.longitud) }
    }

    private fun distanciaKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val radioTierra = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2).let { it * it } +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2).let { it * it }
        return 2 * radioTierra * Math.asin(Math.sqrt(a))
    }

    private fun zonasLocales() = listOf(
        Zona("Lavapiés", 40.4090, -3.7036, 0.8, true),
        Zona("Carabanchel", 40.3847, -3.7312, 1.5, false),
        Zona("Vallecas", 40.3868, -3.6513, 1.5, false),
        Zona("Sol_GranVia", 40.4168, -3.7038, 0.7, true),
        Zona("AZCA", 40.4530, -3.6913, 0.6, true),
        Zona("Becerril_Sierra", 40.7283, -3.8523, 3.0, false),
    )
}