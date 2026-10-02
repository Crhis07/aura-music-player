# Aura Music Player - iOS Lock Screen para Android (Google Pixel 7)

**Aura Music Player** es un reproductor de audio moderno para Android con interfaz de pantalla de bloqueo inspirada en **iOS 16+**, desarrollado utilizando la arquitectura moderna recomendada por Google: **Kotlin, Jetpack Compose y AndroidX Media3 (ExoPlayer)**.

---

## 🌟 Características Principales

### 1. 🔒 Pantalla de Bloqueo Estilo iOS 16
* **Gradiente adaptativo en tiempo real**: Extrae los colores predominantes del álbum con **AndroidX Palette** y genera un fondo dinámico inmersivo.
* **Carátula grande con esquinas redondeadas y sombreado 3D**.
* **Reloj y Fecha tipográfica estilo Apple**: Fecha superior formateada y números de hora de gran tamaño.
* **Cápsula de controles Glassmorphic**: Efecto vidrio translúcido con barra de progreso interactiva (*scrubber*), tiempo transcurrido y cuenta regresiva.
* **Gesto de desbloqueo nativo (*Swipe Up*)**: Deslizar hacia arriba solicita directamente el desbloqueo por huella dactilar o PIN del dispositivo.

### 2. 🎵 Motor de Audio de Alta Fidelidad
* Basado en **AndroidX Media3 (`MediaSessionService`)** para reproducción continua en segundo plano y compatibilidad con auriculares/Bluetooth/Android Auto.
* **Soporte multiformato completo**: MP3, WAV, AIFF, FLAC, ALAC, AAC, OGG y M4A.
* **Fundido cruzado (Crossfade)**: Transiciones suaves entre canciones sin silencios bruscos.

### 3. 📂 Organización Inteligente & Filtros
* Pestañas dedicadas: **Pistas, Álbumes, Artistas, Carpetas y Ajustes**.
* Listas inteligentes: **Más escuchadas**, **Agregadas recientemente** y favoritos con corazón interactivo.
* **Filtro de exclusión automático**: Ignora audios de voz de WhatsApp, grabaciones de llamadas y tonos de notificación breves.

### 4. 📝 Letras Sincronizadas Estilo Spotify (LRCLIB)
* Panel de letras expandible con sincronización en tiempo real línea por línea (formato `.lrc`).
* **Búsqueda e integración automática y manual con LRCLIB**: Descarga la letra sincronizada con un solo toque.
* **Caché permanente en el almacenamiento interno**: La letra se guarda localmente y funciona **100% offline** de por vida, sin volver a gastar datos.
* **Control de ahorro de datos**: Opciones para "Solo Wi-Fi", "Descarga automática" y "Modo Sin Conexión Total".

### 5. ⏱️ Utilidades Adicionales
* **Temporizador de apagado (Sleep Timer)** con cuenta regresiva.
* Selector de salida de audio / Bluetooth / Auracast integrado.
* Ícono de aplicación con diseño circular degradado profesional.

---

## 🛠️ Tecnologías Utilizadas

* **Lenguaje:** Kotlin 1.9+
* **UI:** Jetpack Compose + Material Design 3
* **Audio:** AndroidX Media3 (ExoPlayer 1.4.1)
* **Imágenes:** Coil 2.7.0 + AndroidX Palette
* **Letras:** LRCLIB API con soporte para timestamps `.lrc`
* **Target SDK:** Android 15 (API 35) / Compilado para Google Pixel 7

---

## 🚀 Cómo Compilar e Instalar

### Opción 1: Instalar APK directo
Descarga o transfiere el APK generado a tu teléfono:
`app/build/outputs/apk/debug/app-debug.apk`

### Opción 2: Compilar desde terminal
```bash
./gradlew assembleDebug
```

### Opción 3: Ejecutar desde Android Studio
1. Abre **Android Studio**.
2. Selecciona **Open** y navega a este repositorio.
3. Conecta tu dispositivo Android (ej. Pixel 7) por cable o Wi-Fi Debugging.
4. Presiona **Run ▶️** (`Shift + F10`). 
