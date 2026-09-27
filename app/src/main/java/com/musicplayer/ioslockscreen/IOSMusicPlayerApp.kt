package com.musicplayer.ioslockscreen

import android.app.Application
import android.graphics.Bitmap
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import kotlinx.coroutines.Dispatchers

/**
 * Clase Application personalizada para configurar Coil con rendimiento extremo a 120 FPS
 */
class IOSMusicPlayerApp : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            // Asigna hasta el 25% de la memoria RAM disponible para caché ultra-rápida de carátulas
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            // Caché en almacenamiento para carátulas descargadas de internet
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(50L * 1024 * 1024) // 50 MB
                    .build()
            }
            // Decodificación en hilo IO independiente para no bloquear Compose ni la animación
            .fetcherDispatcher(Dispatchers.IO)
            .decoderDispatcher(Dispatchers.IO)
            .respectCacheHeaders(false)
            .build()
    }
}
