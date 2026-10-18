# SafeRouteAI · App Android

Aplicación Android que muestra en un mapa de Madrid el nivel de riesgo de una zona (verde, amarillo o rojo) según la hora y el día, con un consejo para el usuario. Es mi proyecto final del ciclo de Desarrollo de Aplicaciones Multiplataforma (DAM, junio de 2026).

> **Sobre los datos:** las predicciones las genera un modelo entrenado con datos **simulados**, no con datos delictivos reales. Es un proyecto de aprendizaje y no sirve para decisiones de seguridad. Más detalles en el [repositorio del backend](https://github.com/cristian-corroto/saferoute-ai-backend).
>
> **Sobre el desarrollo:** lo he hecho con apoyo intensivo de IA en casi todas sus partes.

## Qué hace

- Muestra un mapa de Madrid (Google Maps) y tu posición por GPS.
- Permite tocar una zona, buscar una dirección o cambiar la hora con un deslizador.
- Consulta al backend la predicción para esas coordenadas, hora y día.
- Muestra el resultado en un panel inferior: color, porcentaje y consejo.

## Tecnologías

Kotlin · Android (minSdk 26) · Google Maps SDK · Retrofit · ViewModel y corrutinas

El backend (Python, FastAPI y scikit-learn) está en su propio repositorio: [saferoute-ai-backend](https://github.com/cristian-corroto/saferoute-ai-backend).

## Cómo compilarla

1. Abre el proyecto en Android Studio.
2. Copia `local.properties.example` como `local.properties` y rellena:
   - `sdk.dir`: la ruta a tu Android SDK (Android Studio suele crearla solo).
   - `MAPS_API_KEY`: tu propia clave de Google Maps con *Maps SDK for Android* activada. La clave **no** está en el repositorio.
3. Ejecuta la app en un emulador o en un móvil.

La app usa el servidor de demostración definido en `app/src/main/java/com/example/ai/data/Apiclient.kt`. Es un servidor gratuito: la primera consulta puede tardar al despertar el servicio.

## Estructura

| Archivo | Función |
|---|---|
| `MainActivity.kt` | Pantalla principal: mapa, buscador, deslizador y panel |
| `ui/MapViewModel.kt` | Estado de la pantalla y consultas al servidor |
| `data/Apiclient.kt` | Configuración de Retrofit |
| `data/Models.kt` | Modelos de petición y respuesta |

## Limitaciones

- Datos simulados y solo 6 zonas de referencia (ver el backend).
- Necesita conexión a Internet para consultar la predicción.

## Autor

Cristian Corroto · Descarga del APK: <https://saferouteai-app.netlify.app/>
