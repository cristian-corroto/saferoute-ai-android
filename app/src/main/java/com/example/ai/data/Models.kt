// SafeRouteAI — Models.kt
// Ruta: app/src/main/java/com/saferoute/ai/data/Models.kt
//
// Estos son los modelos de datos que representan
// lo que enviamos y recibimos de la API FastAPI.

package com.example.ai.data

import com.google.gson.annotations.SerializedName

// ─────────────────────────────────────────────
// LO QUE ENVIAMOS A LA API (/predecir)
// ─────────────────────────────────────────────

data class PrediccionRequest(
    @SerializedName("latitud")    val latitud: Double,
    @SerializedName("longitud")   val longitud: Double,
    @SerializedName("hora")       val hora: Int,
    @SerializedName("dia_semana") val diaSemana: Int,
    @SerializedName("densidad")   val densidad: Int = 50
)

// ─────────────────────────────────────────────
// LO QUE RECIBIMOS DE LA API (/predecir)
// ─────────────────────────────────────────────

data class PrediccionResponse(
    @SerializedName("zona")              val zona: String,
    @SerializedName("distancia_km")      val distanciaKm: Double,
    @SerializedName("nivel_riesgo")      val nivelRiesgo: String,   // "verde", "amarillo", "rojo"
    @SerializedName("probabilidad_pct")  val probabilidadPct: Double,
    @SerializedName("color")             val color: String,         // "#639922", "#EF9F27", "#E24B4A"
    @SerializedName("consejo")           val consejo: String,
    @SerializedName("hora")              val hora: Int,
    @SerializedName("dia_semana")        val diaSemana: Int
)

// ─────────────────────────────────────────────
// ZONA (para /zonas)
// ─────────────────────────────────────────────

data class Zona(
    @SerializedName("nombre")        val nombre: String,
    @SerializedName("latitud")       val latitud: Double,
    @SerializedName("longitud")      val longitud: Double,
    @SerializedName("radio_km")      val radioKm: Double,
    @SerializedName("ocio_nocturno") val ocioNocturno: Boolean
)

data class ZonasResponse(
    @SerializedName("zonas") val zonas: List<Zona>
)

// ─────────────────────────────────────────────
// ESTADO DE LA PREDICCIÓN (para la UI)
// ─────────────────────────────────────────────

sealed class EstadoPrediccion {
    object Cargando : EstadoPrediccion()
    data class Exito(val respuesta: PrediccionResponse) : EstadoPrediccion()
    data class Error(val mensaje: String) : EstadoPrediccion()
    object Idle : EstadoPrediccion()
}