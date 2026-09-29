package pl.mediaslim

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Metadata
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.Mp4TimestampData
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMp4Muxer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

sealed class RezultatKodowania {
    data class Udane(val plik: File, val rozmiar: Long) : RezultatKodowania()
    data class Blad(val opis: String) : RezultatKodowania()
    object Przerwane : RezultatKodowania()
}

@OptIn(UnstableApi::class)
object Kodowanie {

    /**
     * Koduje jeden film do HEVC.
     *
     * Skalowanie: Presentation.createForShortSide ogranicza KROTSZY bok,
     * a dluzszy skaluje proporcjonalnie. To odpowiednik ramki kwadratowej
     * z wersji na Windows i - co wazne - dziala tak samo dla filmow
     * poziomych i pionowych, bez wnikania, w ktorym momencie potoku
     * stosowany jest obrot z metadanych.
     *
     * Data nagrania: Transformer przepisuje metadane wejscia, ale dla
     * pewnosci wpisujemy ja jeszcze raz przez MetadataProvider. Bez tego
     * galeria pokazalaby film z dzisiejsza data i cale archiwum
     * przeskoczyloby na gore osi czasu.
     */
    suspend fun zakoduj(
        ctx: Context,
        film: Film,
        plikWyjsciowy: File,
        postep: (Int) -> Unit
    ): RezultatKodowania = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { kont: CancellableContinuation<RezultatKodowania> ->

            val czasNagrania =
                if (film.dataZrobienia > 0) film.dataZrobienia else film.dataModyfikacji

            val ustawieniaKodera = VideoEncoderSettings.Builder()
                .setBitrate(film.bitrateDocelowy)
                .build()

            val muxer = InAppMp4Muxer.Factory(
                InAppMp4Muxer.MetadataProvider { wpisy: MutableSet<Metadata.Entry> ->
                    wpisy.removeAll { it is Mp4TimestampData }
                    if (czasNagrania > 0) {
                        val s = Mp4TimestampData.unixTimeToMp4TimeSeconds(czasNagrania)
                        wpisy.add(Mp4TimestampData(s, s))
                    }
                }
            )

            val transformer = Transformer.Builder(ctx)
                .setVideoMimeType(MimeTypes.VIDEO_H265)
                .setEncoderFactory(
                    DefaultEncoderFactory.Builder(ctx)
                        .setRequestedVideoEncoderSettings(ustawieniaKodera)
                        .setEnableFallback(true)
                        .build()
                )
                .setMuxerFactory(muxer)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(
                        composition: Composition,
                        exportResult: ExportResult
                    ) {
                        if (kont.isActive) {
                            val rozmiar = if (plikWyjsciowy.exists()) plikWyjsciowy.length() else 0L
                            kont.resume(
                                if (rozmiar > 0) RezultatKodowania.Udane(plikWyjsciowy, rozmiar)
                                else RezultatKodowania.Blad("pusty plik wynikowy")
                            )
                        }
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        if (kont.isActive) {
                            kont.resume(
                                RezultatKodowania.Blad(
                                    exportException.message ?: "kodowanie nie powiodło się"
                                )
                            )
                        }
                    }
                })
                .build()

            val efekty: Effects = if (film.docelowyKrotszyBok > 0) {
                Effects(
                    emptyList<AudioProcessor>(),
                    listOf<Effect>(Presentation.createForShortSide(film.docelowyKrotszyBok))
                )
            } else {
                Effects(emptyList<AudioProcessor>(), emptyList<Effect>())
            }

            val pozycja = EditedMediaItem.Builder(MediaItem.fromUri(film.uri))
                .setEffects(efekty)
                .build()

            // Postep odpytujemy cyklicznie - Transformer nie wola nas sam.
            val uchwyt = Handler(Looper.getMainLooper())
            val licznik = ProgressHolder()
            val odpytywanie = object : Runnable {
                override fun run() {
                    if (!kont.isActive) return
                    val stan = transformer.getProgress(licznik)
                    if (stan == Transformer.PROGRESS_STATE_AVAILABLE) postep(licznik.progress)
                    uchwyt.postDelayed(this, 700L)
                }
            }

            kont.invokeOnCancellation {
                // cancel() musi isc z watku, na ktorym wystartowal Transformer
                uchwyt.post {
                    try {
                        transformer.cancel()
                    } catch (e: Exception) {
                        // juz zakonczony
                    }
                    if (plikWyjsciowy.exists()) plikWyjsciowy.delete()
                }
                uchwyt.removeCallbacks(odpytywanie)
            }

            try {
                transformer.start(pozycja, plikWyjsciowy.absolutePath)
                uchwyt.postDelayed(odpytywanie, 700L)
            } catch (e: Exception) {
                if (kont.isActive) {
                    kont.resume(RezultatKodowania.Blad(e.message ?: "nie udało się wystartować"))
                }
            }
        }
    }

    /** Ile miejsca zostalo tam, gdzie zapisujemy wyniki. */
    fun wolneMiejsce(katalog: File): Long = try {
        katalog.usableSpace
    } catch (e: Exception) {
        Long.MAX_VALUE
    }
}
