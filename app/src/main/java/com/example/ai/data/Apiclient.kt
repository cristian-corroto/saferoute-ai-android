// SafeRouteAI — ApiClient.kt
// Ruta: app/src/main/java/com/saferoute/ai/data/ApiClient.kt
//
// Configura Retrofit para hablar con nuestra API FastAPI.
// IMPORTANTE: Cambia BASE_URL si tu API corre en otro puerto
// o si la subes a un servidor en la nube.

package com.example.ai.data

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

// ─────────────────────────────────────────────
// URL BASE DE LA API
// Durante desarrollo: tu ordenador y el móvil
// deben estar en la misma red WiFi.
// 10.0.2.2 es la IP especial del emulador
// para referirse al localhost de tu PC.
// ─────────────────────────────────────────────

object ApiClient {

    // Para emulador de Android Studio:
    private const val BASE_URL_EMULADOR = "https://saferoute-ai-backend-kt6q.onrender.com/"

    // Para móvil físico (pon la IP de tu PC en la red WiFi):
    // Puedes verla con: ipconfig (Windows) o ifconfig (Mac/Linux)
    // Ejemplo: private const val BASE_URL_MOVIL = "http://10.0.2.2:8000/"

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL_EMULADOR)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val service: SafeRouteService = retrofit.create(SafeRouteService::class.java)
}

// ─────────────────────────────────────────────
// INTERFAZ DE LA API (Retrofit)
// Cada función = un endpoint de FastAPI
// ─────────────────────────────────────────────

interface SafeRouteService {

    @POST("predecir")
    suspend fun predecirRiesgo(
        @Body request: PrediccionRequest
    ): PrediccionResponse

    @GET("zonas")
    suspend fun obtenerZonas(): ZonasResponse

    @POST("train")
    suspend fun entrenarModelo(): Map<String, Any>
}