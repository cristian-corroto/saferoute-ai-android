package com.example.ai

import android.Manifest
import android.animation.ObjectAnimator
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.LayerDrawable
import android.location.Geocoder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.ai.data.EstadoPrediccion
import com.example.ai.data.PrediccionResponse
import com.example.ai.data.Zona
import com.example.ai.databinding.ActivityMainBinding
import com.example.ai.ui.MapViewModel
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.Circle
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.material.bottomsheet.BottomSheetBehavior
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MapViewModel by viewModels()
    private lateinit var mapa: GoogleMap
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<View>

    private var marcadorUsuario: Marker? = null
    private val circulos = mutableMapOf<String, Circle>()
    private val datosZonas = mutableListOf<Zona>()
    private var circuloBusqueda: Circle? = null

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var runnableReloj: Runnable

    private var isDraggingHora = false
    private var pendingHora = -1
    private var userInteractingWithSheetControls = false
    private var debounceHoraJob: Job? = null

    private val MADRID_CENTRO = LatLng(40.4168, -3.7038)
    private val ZOOM_INICIAL = 11f

    private val pedirPermiso = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permisos ->
        if (permisos[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            activarMiUbicacion()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.layoutHeader) { view, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(view.paddingLeft, statusBarHeight + 12, view.paddingRight, view.paddingBottom)
            insets
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        configurarBottomSheet()
        configurarControles()
        configurarBuscador()
        observarViewModel()
        iniciarRelojBotonAhora()

        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(runnableReloj)
        debounceHoraJob?.cancel()
    }

    private fun configurarBottomSheet() {
        bottomSheetBehavior = BottomSheetBehavior.from(binding.bottomSheet)

        binding.bottomSheet.post {
            val density = resources.displayMetrics.density
            bottomSheetBehavior.peekHeight = (126 * density).toInt()
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
            bottomSheetBehavior.isHideable = false
            bottomSheetBehavior.skipCollapsed = false
            bottomSheetBehavior.isFitToContents = false
            bottomSheetBehavior.halfExpandedRatio = 0.60f
            bottomSheetBehavior.expandedOffset = (88 * density).toInt()
            bottomSheetBehavior.isDraggable = true
        }

        bottomSheetBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                when (newState) {
                    BottomSheetBehavior.STATE_COLLAPSED -> {
                        binding.sheetHandle.alpha = 1f
                        binding.mapScrim.alpha = 0.08f
                    }
                    BottomSheetBehavior.STATE_HALF_EXPANDED,
                    BottomSheetBehavior.STATE_EXPANDED -> {
                        binding.sheetHandle.alpha = 0.72f
                    }
                }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                val offset = slideOffset.coerceIn(0f, 1f)
                binding.mapScrim.alpha = 0.08f + (offset * 0.16f)
                binding.sheetHandle.alpha = 1f - (offset * 0.30f)
            }
        })

        binding.sheetCollapsedHeader.setOnClickListener {
            if (bottomSheetBehavior.state == BottomSheetBehavior.STATE_COLLAPSED) {
                expandirPanel()
            } else {
                colapsarPanel()
            }
        }
    }

    private fun expandirPanel() {
        if (!isDraggingHora) {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_HALF_EXPANDED
        }
    }

    private fun colapsarPanel() {
        if (!isDraggingHora) {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        }
    }

    private fun bloquearInteraccionSheet() {
        userInteractingWithSheetControls = true
        bottomSheetBehavior.isDraggable = false
    }

    private fun desbloquearInteraccionSheet(delayMs: Long = 180L) {
        binding.bottomSheet.postDelayed({
            userInteractingWithSheetControls = false
            if (!isDraggingHora) {
                bottomSheetBehavior.isDraggable = true
            }
        }, delayMs)
    }

    private fun configurarBuscador() {
        binding.etBuscar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val texto = binding.etBuscar.text.toString().trim()
                if (texto.isNotEmpty()) buscarLugar(texto)
                true
            } else {
                false
            }
        }

        binding.btnBuscar.setOnClickListener {
            val texto = binding.etBuscar.text.toString().trim()
            if (texto.isNotEmpty()) buscarLugar(texto)
        }

        binding.btnLimpiarBusqueda.setOnClickListener {
            limpiarBusqueda()
        }
    }

    private fun buscarLugar(query: String) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etBuscar.windowToken, 0)
        binding.progressBar.visibility = View.VISIBLE
        binding.tvResumenEstado.text = "Buscando zona..."
        binding.tvResumenEstado.setTextColor(Color.parseColor("#8892A4"))

        Thread {
            try {
                val geocoder = Geocoder(this, Locale("es", "ES"))
                @Suppress("DEPRECATION")
                val resultados = geocoder.getFromLocationName("$query, España", 3)

                runOnUiThread {
                    binding.progressBar.visibility = View.GONE

                    if (resultados.isNullOrEmpty()) {
                        binding.tvResumenEstado.text = "No se encontró la zona"
                        Toast.makeText(this, "No se encontró \"$query\"", Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }

                    val lugar = resultados[0]
                    val latLng = LatLng(lugar.latitude, lugar.longitude)
                    val nombreLugar = lugar.locality
                        ?: lugar.subAdminArea
                        ?: lugar.adminArea
                        ?: query

                    mapa.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 13f))
                    moverMarcadorUsuario(latLng)
                    binding.btnLimpiarBusqueda.visibility = View.VISIBLE
                    binding.tvResumenZona.text = nombreLugar
                    binding.tvResumenEstado.text = "Analizando riesgo..."
                    binding.tvResumenEstado.setTextColor(Color.parseColor("#8892A4"))
                    viewModel.buscarYPredecir(nombreLugar, lugar.latitude, lugar.longitude)
                    colapsarPanel()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    binding.tvResumenEstado.text = "Error al buscar"
                    Toast.makeText(this, "Error al buscar: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun limpiarBusqueda() {
        binding.etBuscar.setText("")
        binding.btnLimpiarBusqueda.visibility = View.GONE
        binding.cardResultado.visibility = View.GONE
        binding.tvResumenZona.text = "Sin zona seleccionada"
        binding.tvResumenEstado.text = "Toca el mapa o busca una zona"
        binding.tvResumenEstado.setTextColor(Color.parseColor("#8892A4"))

        marcadorUsuario?.remove()
        marcadorUsuario = null
        circuloBusqueda?.remove()
        circuloBusqueda = null

        circulos.values.forEach { it.remove() }
        circulos.clear()

        viewModel.limpiarBusqueda()
        mapa.animateCamera(CameraUpdateFactory.newLatLngZoom(MADRID_CENTRO, ZOOM_INICIAL))
        colapsarPanel()
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mapa = googleMap

        mapa.setMapStyle(
            MapStyleOptions(
                """
                [
                  {"elementType":"geometry","stylers":[{"color":"#0d1b2e"}]},
                  {"elementType":"labels.text.fill","stylers":[{"color":"#8892a4"}]},
                  {"elementType":"labels.text.stroke","stylers":[{"color":"#0d1117"}]},
                  {"featureType":"road","elementType":"geometry.fill","stylers":[{"color":"#1e2d45"}]},
                  {"featureType":"road.highway","elementType":"geometry","stylers":[{"color":"#2c4468"}]},
                  {"featureType":"poi.park","elementType":"geometry","stylers":[{"color":"#0f2318"}]},
                  {"featureType":"water","elementType":"geometry","stylers":[{"color":"#081524"}]},
                  {"featureType":"administrative","elementType":"geometry","stylers":[{"color":"#1e2636"}]},
                  {"featureType":"transit","elementType":"labels.text.fill","stylers":[{"color":"#5a6478"}]}
                ]
                """.trimIndent()
            )
        )

        mapa.moveCamera(CameraUpdateFactory.newLatLngZoom(MADRID_CENTRO, ZOOM_INICIAL))
        mapa.uiSettings.apply {
            isZoomControlsEnabled = true
            isCompassEnabled = true
            isMyLocationButtonEnabled = false
        }

        pedirPermisoLocalizacion()

        mapa.setOnMapClickListener { latLng ->
            val zonaClicada = detectarZonaClicada(latLng)

            if (zonaClicada != null) {
                resaltarZona(zonaClicada)
                moverMarcadorUsuario(latLng)
                binding.tvResumenZona.text = zonaClicada.nombre.replace("_", " ")
                binding.tvResumenEstado.text = "Analizando zona..."
                binding.tvResumenEstado.setTextColor(Color.parseColor("#8892A4"))
                viewModel.predecirRiesgo(zonaClicada.latitud, zonaClicada.longitud)
            } else {
                moverMarcadorUsuario(latLng)
                binding.tvResumenZona.text = "Punto analizado"
                binding.tvResumenEstado.text = "Analizando zona..."
                binding.tvResumenEstado.setTextColor(Color.parseColor("#8892A4"))
                viewModel.predecirRiesgo(latLng.latitude, latLng.longitude)
            }

            expandirPanel()
        }

        lifecycleScope.launch {
            viewModel.zonas.collect { zonas ->
                datosZonas.clear()
                datosZonas.addAll(zonas)
            }
        }

        lifecycleScope.launch {
            viewModel.zonaBuscada.collect { zona ->
                circuloBusqueda?.remove()
                circuloBusqueda = null
                circulos.values.forEach { it.remove() }
                circulos.clear()

                if (zona != null) {
                    pintarZonaBuscada(zona)
                    binding.tvResumenZona.text = zona.nombre.replace("_", " ")
                } else {
                    binding.tvResumenZona.text = "Sin zona seleccionada"
                    binding.tvResumenEstado.text = "Toca el mapa o busca una zona"
                    binding.tvResumenEstado.setTextColor(Color.parseColor("#8892A4"))
                }
            }
        }

        lifecycleScope.launch {
            viewModel.estado.collect { estado ->
                if (estado is EstadoPrediccion.Exito && viewModel.zonaBuscada.value != null) {
                    actualizarColorCirculoBusqueda(estado.respuesta)
                }
            }
        }
    }

    private fun pintarZonaBuscada(zona: Zona) {
        val centro = LatLng(zona.latitud, zona.longitud)
        val colorBase = Color.parseColor("#8892A4")

        circuloBusqueda = mapa.addCircle(
            CircleOptions()
                .center(centro)
                .radius(zona.radioKm * 1000)
                .fillColor(Color.argb(90, Color.red(colorBase), Color.green(colorBase), Color.blue(colorBase)))
                .strokeColor(Color.argb(220, Color.red(colorBase), Color.green(colorBase), Color.blue(colorBase)))
                .strokeWidth(3f)
        )
    }

    private fun actualizarColorCirculoBusqueda(prediccion: PrediccionResponse) {
        val circulo = circuloBusqueda ?: return
        val color = Color.parseColor(prediccion.color)
        circulo.fillColor = Color.argb(90, Color.red(color), Color.green(color), Color.blue(color))
        circulo.strokeColor = Color.argb(220, Color.red(color), Color.green(color), Color.blue(color))
        circulo.strokeWidth = 3.5f
    }

    private fun detectarZonaClicada(latLng: LatLng): Zona? {
        return datosZonas.firstOrNull { zona ->
            distanciaMetros(latLng, LatLng(zona.latitud, zona.longitud)) <= zona.radioKm * 1000
        }
    }

    private fun distanciaMetros(a: LatLng, b: LatLng): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = Math.sin(dLat / 2).let { it * it } +
                Math.cos(Math.toRadians(a.latitude)) * Math.cos(Math.toRadians(b.latitude)) *
                Math.sin(dLon / 2).let { it * it }
        return 2 * r * Math.asin(Math.sqrt(h))
    }

    private fun resaltarZona(zona: Zona) {
        circulos.forEach { (nombre, circulo) ->
            val color = colorPorZona(nombre)
            circulo.fillColor = Color.argb(70, Color.red(color), Color.green(color), Color.blue(color))
            circulo.strokeColor = Color.argb(200, Color.red(color), Color.green(color), Color.blue(color))
            circulo.strokeWidth = 2.5f
        }

        val circulo = circulos[zona.nombre] ?: return
        val color = colorPorZona(zona.nombre)
        circulo.fillColor = Color.argb(140, Color.red(color), Color.green(color), Color.blue(color))
        circulo.strokeColor = Color.argb(255, Color.red(color), Color.green(color), Color.blue(color))
        circulo.strokeWidth = 4f
    }

    private fun colorPorZona(nombre: String): Int = when (nombre) {
        "Lavapiés", "Carabanchel", "Vallecas" -> Color.parseColor("#E24B4A")
        "Sol_GranVia", "AZCA" -> Color.parseColor("#EF9F27")
        "Becerril_Sierra" -> Color.parseColor("#1DDB94")
        else -> Color.parseColor("#8892A4")
    }

    private fun moverMarcadorUsuario(latLng: LatLng) {
        marcadorUsuario?.remove()
        marcadorUsuario = mapa.addMarker(
            MarkerOptions()
                .position(latLng)
                .title("Punto analizado")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
        )
    }

    private fun pedirPermisoLocalizacion() {
        when {
            tienePermiso(Manifest.permission.ACCESS_FINE_LOCATION) -> activarMiUbicacion()
            else -> pedirPermiso.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun activarMiUbicacion() {
        if (tienePermiso(Manifest.permission.ACCESS_FINE_LOCATION)) {
            mapa.isMyLocationEnabled = true
        }
    }

    private fun usarUbicacionGPS() {
        if (!tienePermiso(Manifest.permission.ACCESS_FINE_LOCATION)) {
            pedirPermisoLocalizacion()
            return
        }

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                val latLng = LatLng(location.latitude, location.longitude)
                mapa.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
                moverMarcadorUsuario(latLng)
                binding.tvResumenZona.text = "Mi ubicación"
                binding.tvResumenEstado.text = "Analizando zona..."
                binding.tvResumenEstado.setTextColor(Color.parseColor("#8892A4"))
                viewModel.predecirRiesgo(latLng.latitude, latLng.longitude)
                expandirPanel()
            } else {
                Toast.makeText(this, "No se pudo obtener tu ubicación GPS", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun tienePermiso(permiso: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permiso) == PackageManager.PERMISSION_GRANTED
    }

    private fun configurarControles() {
        binding.seekHora.max = 23
        binding.seekHora.progress = viewModel.hora.value
        actualizarLabelHora(viewModel.hora.value)

        binding.seekHora.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                actualizarLabelHora(prog)
                if (fromUser) {
                    pendingHora = prog
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                isDraggingHora = true
                bloquearInteraccionSheet()
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                isDraggingHora = false

                val horaFinal = if (pendingHora >= 0) pendingHora else (sb?.progress ?: viewModel.hora.value)
                pendingHora = -1

                debounceHoraJob?.cancel()
                debounceHoraJob = lifecycleScope.launch {
                    delay(180)
                    viewModel.setHora(horaFinal)
                    desbloquearInteraccionSheet(120)
                }
            }
        })

        val chipsDia = listOf(
            binding.chipLun, binding.chipMar, binding.chipMie,
            binding.chipJue, binding.chipVie, binding.chipSab, binding.chipDom
        )

        chipsDia.forEachIndexed { index, chip ->
            chip.setOnClickListener {
                bloquearInteraccionSheet()
                chipsDia.forEach { it.isSelected = false }
                chip.isSelected = true
                viewModel.setDiaSemana(index)
                desbloquearInteraccionSheet()
            }
        }
        chipsDia[viewModel.diaSemana.value].isSelected = true

        binding.btnGps.setOnClickListener {
            bloquearInteraccionSheet()
            usarUbicacionGPS()
            desbloquearInteraccionSheet(250)
        }

        binding.btnAhora.setOnClickListener {
            bloquearInteraccionSheet()

            viewModel.usarHoraActual()
            binding.seekHora.progress = viewModel.hora.value
            actualizarLabelHora(viewModel.hora.value)

            val dia = viewModel.diaSemana.value
            chipsDia.forEach { it.isSelected = false }
            chipsDia[dia].isSelected = true

            Toast.makeText(this, "Usando hora y día actuales", Toast.LENGTH_SHORT).show()
            desbloquearInteraccionSheet()
        }
    }

    private fun actualizarLabelHora(hora: Int) {
        val sufijo = if (hora < 12) "AM" else "PM"
        val hora12 = when {
            hora == 0 -> "12"
            hora <= 12 -> hora.toString()
            else -> (hora - 12).toString()
        }
        binding.tvHora.text = "${hora.toString().padStart(2, '0')}:00 ($hora12$sufijo)"
    }

    private fun iniciarRelojBotonAhora() {
        runnableReloj = object : Runnable {
            override fun run() {
                val cal = Calendar.getInstance()
                binding.btnAhora.text = "🕐 %02d:%02d".format(
                    cal.get(Calendar.HOUR_OF_DAY),
                    cal.get(Calendar.MINUTE)
                )
                handler.postDelayed(this, 30_000)
            }
        }
        handler.post(runnableReloj)
    }

    private fun observarViewModel() {
        lifecycleScope.launch {
            viewModel.estado.collect { estado ->
                when (estado) {
                    is EstadoPrediccion.Cargando -> {
                        binding.progressBar.visibility = View.VISIBLE
                        binding.cardResultado.visibility = View.GONE
                        binding.tvResumenEstado.text = "Analizando zona..."
                        binding.tvResumenEstado.setTextColor(Color.parseColor("#8892A4"))
                    }

                    is EstadoPrediccion.Exito -> {
                        binding.progressBar.visibility = View.GONE
                        mostrarResultado(estado.respuesta)
                    }

                    is EstadoPrediccion.Error -> {
                        binding.progressBar.visibility = View.GONE
                        binding.tvResumenEstado.text = "Error al analizar"
                        binding.tvResumenEstado.setTextColor(Color.parseColor("#E24B4A"))
                        Toast.makeText(this@MainActivity, estado.mensaje, Toast.LENGTH_LONG).show()
                    }

                    is EstadoPrediccion.Idle -> {
                        binding.progressBar.visibility = View.GONE
                    }
                }
            }
        }

        lifecycleScope.launch {
            viewModel.densidad.collect { d ->
                binding.tvDensidad.text = "$d%"
            }
        }
    }

    private fun mostrarResultado(respuesta: PrediccionResponse) {
        binding.cardResultado.visibility = View.VISIBLE
        binding.cardResultado.translationY = 44f
        binding.cardResultado.alpha = 0f
        binding.cardResultado.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(320)
            .setInterpolator(DecelerateInterpolator())
            .start()

        val colorHex = when (respuesta.nivelRiesgo) {
            "rojo" -> "#E24B4A"
            "amarillo" -> "#EF9F27"
            else -> "#1DDB94"
        }

        val icono = when (respuesta.nivelRiesgo) {
            "rojo" -> "🔴"
            "amarillo" -> "🟡"
            else -> "🟢"
        }

        val etiqueta = when (respuesta.nivelRiesgo) {
            "rojo" -> "ALTO RIESGO"
            "amarillo" -> "PRECAUCIÓN"
            else -> "ZONA SEGURA"
        }

        val color = Color.parseColor(colorHex)
        binding.viewRiskStripe.setBackgroundColor(color)
        binding.tvNivelIcono.text = icono
        binding.tvNivel.text = etiqueta
        binding.tvNivel.setTextColor(color)
        binding.tvZona.text = respuesta.zona.replace("_", " ")
        binding.tvProbabilidad.text = "${respuesta.probabilidadPct.toInt()}%"
        binding.tvProbabilidad.setTextColor(color)
        binding.tvConsejo.text = respuesta.consejo
        binding.tvResumenEstado.text = etiqueta
        binding.tvResumenEstado.setTextColor(color)

        val dias = listOf("Lun", "Mar", "Mié", "Jue", "Vie", "Sáb", "Dom")
        binding.tvDetalle.text =
            "${respuesta.hora.toString().padStart(2, '0')}:00 · ${dias.getOrElse(respuesta.diaSemana) { "?" }}"

        val pct = respuesta.probabilidadPct.toInt()
        binding.progressRiesgo.progress = 0
        ObjectAnimator.ofInt(binding.progressRiesgo, "progress", 0, pct).apply {
            duration = 580
            interpolator = DecelerateInterpolator()
            start()
        }

        val layerDrawable = binding.progressRiesgo.progressDrawable.mutate() as? LayerDrawable
        layerDrawable?.findDrawableByLayerId(android.R.id.progress)?.colorFilter =
            android.graphics.BlendModeColorFilter(color, android.graphics.BlendMode.SRC_IN)

        if (!isDraggingHora && !userInteractingWithSheetControls) {
            colapsarPanel()
        }
    }
}