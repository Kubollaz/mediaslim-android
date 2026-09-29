package pl.mediaslim

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Kodowanie chodzi w usludze pierwszoplanowej, zeby telefon nie ubil go
 * po zgaszeniu ekranu. Dwa sposoby zatrzymania, tak jak w wersji na komputer:
 * "po bieżącym" konczy plik i odklada reszte, "przerwij teraz" ubija kodowanie
 * i kasuje niedokonczony plik.
 */
class UslugaKodowania : Service() {

    companion object {
        const val START = "pl.mediaslim.START"
        const val STOP_PO_PLIKU = "pl.mediaslim.STOP_PO_PLIKU"
        const val STOP_TERAZ = "pl.mediaslim.STOP_TERAZ"
        private const val KANAL = "kodowanie"
        private const val ID_POWIADOMIENIA = 1

        fun uruchom(ctx: Context) {
            val i = Intent(ctx, UslugaKodowania::class.java).setAction(START)
            ctx.startForegroundService(i)
        }

        fun polecenie(ctx: Context, akcja: String) {
            ctx.startService(Intent(ctx, UslugaKodowania::class.java).setAction(akcja))
        }
    }

    private val zakres = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var zadanie: Job? = null
    private var zadaniePliku: Job? = null
    private var blokadaCzuwania: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flagi: Int, id: Int): Int {
        when (intent?.action) {
            STOP_PO_PLIKU -> {
                Stan.zmien { it.copy(stopPoPliku = true) }
                Stan.dopisz(">>> Zatrzymam po dokończeniu bieżącego pliku")
                if (zadanie?.isActive == true) powiadom("Kończę bieżący plik…", "")
                else stopSelf()
            }
            STOP_TERAZ -> {
                Stan.zmien { it.copy(stopPoPliku = true, stopTeraz = true) }
                Stan.dopisz(">>> Przerywam natychmiast")
                zadaniePliku?.cancel()
                if (zadanie?.isActive != true) stopSelf()
            }
            else -> {
                if (zadanie?.isActive != true) {
                    Stan.uslugaDziala = true
                    startForegroundBezpiecznie("Przygotowuję…", "")
                    zadanie = zakres.launch { pracuj() }
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun pracuj() {
        val baza = Baza(this)
        val profil = Ustawienia.profil(this)
        val katalog = File(getExternalFilesDir(null), "wyniki").apply { mkdirs() }
        val kolejka = Stan.kolejka
        val menedzerZasilania = getSystemService(Context.POWER_SERVICE) as PowerManager

        blokadaCzuwania = menedzerZasilania.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "mediaslim:kodowanie"
        ).also { it.acquire(6 * 60 * 60 * 1000L) }

        Stan.zmien {
            it.copy(
                trwa = true, etykieta = "Kodowanie", numer = 0, razem = kolejka.size,
                poczatek = System.currentTimeMillis(), sekundyZrobione = 0.0,
                sekundyRazem = kolejka.sumOf { f -> f.czasMs / 1000.0 }.coerceAtLeast(1.0),
                odzyskane = 0L, procentPliku = 0
            )
        }

        var udane = 0
        var odrzucone = 0
        var bledy = 0

        try {
            for ((indeks, film) in kolejka.withIndex()) {
                if (Stan.praca.value.stopPoPliku || Stan.praca.value.stopTeraz) break

                Stan.zmien {
                    it.copy(numer = indeks + 1, plik = film.nazwa, procentPliku = 0)
                }
                powiadom(
                    "Kodowanie ${indeks + 1}/${kolejka.size}",
                    film.nazwa
                )

                // Telefon gorący - dajmy mu ochłonąć, inaczej system i tak zdławi koder
                poczekajNaOchlodzenie(menedzerZasilania)

                val wynikPliku = File(katalog, "${film.id}_${System.currentTimeMillis()}.mp4")

                if (Kodowanie.wolneMiejsce(katalog) < film.szacowanyRozmiar * 2 + 300L * 1024 * 1024) {
                    Stan.dopisz("BRAK MIEJSCA - przerywam przed ${film.nazwa}")
                    break
                }

                var rezultat: RezultatKodowania = RezultatKodowania.Przerwane
                val praca = zakres.launch {
                    rezultat = Kodowanie.zakoduj(this@UslugaKodowania, film, wynikPliku) { p ->
                        Stan.zmien { it.copy(procentPliku = p) }
                    }
                }
                zadaniePliku = praca
                try {
                    praca.join()
                } catch (e: CancellationException) {
                    rezultat = RezultatKodowania.Przerwane
                }
                zadaniePliku = null

                if (Stan.praca.value.stopTeraz) {
                    if (wynikPliku.exists()) wynikPliku.delete()
                    Stan.dopisz("PRZERWANE - niedokończony plik skasowany: ${film.nazwa}")
                    break
                }

                Stan.zmien {
                    it.copy(sekundyZrobione = it.sekundyZrobione + film.czasMs / 1000.0)
                }

                when (val r = rezultat) {
                    is RezultatKodowania.Blad -> {
                        if (wynikPliku.exists()) wynikPliku.delete()
                        zapiszWpis(baza, film, "blad", r.opis)
                        bledy++
                        Stan.dopisz("BŁĄD ${film.nazwa}: ${r.opis}")
                    }

                    is RezultatKodowania.Przerwane -> {
                        if (wynikPliku.exists()) wynikPliku.delete()
                    }

                    is RezultatKodowania.Udane -> {
                        val powod = Weryfikacja.sprawdz(film, r.plik, film.dataZrobienia > 0)
                        if (powod != null) {
                            r.plik.delete()
                            zapiszWpis(baza, film, "odrzucony", powod)
                            odrzucone++
                            Stan.dopisz("ODRZUCONY ${film.nazwa}: $powod")
                        } else {
                            val proc = Math.round(100.0 * r.rozmiar / film.rozmiar)
                            zapiszWpis(
                                baza, film, "zakodowany", "$proc% oryginału",
                                r.plik.absolutePath, r.rozmiar
                            )
                            udane++
                            Stan.zmien {
                                it.copy(odzyskane = it.odzyskane + (film.rozmiar - r.rozmiar))
                            }
                            Stan.dopisz(
                                "GOTOWE ${film.nazwa}: ${waga(film.rozmiar)} -> " +
                                    "${waga(r.rozmiar)} ($proc%)"
                            )
                        }
                    }
                }
                Stan.odswiezListy()
            }
        } finally {
            baza.close()
            try {
                blokadaCzuwania?.let { if (it.isHeld) it.release() }
            } catch (e: Exception) {
                // nic
            }
            blokadaCzuwania = null
        }

        Stan.dopisz("Zakodowane $udane | odrzucone $odrzucone | błędy $bledy")
        Stan.uslugaDziala = false
        Stan.wyczyscPoPracy()
        Stan.odswiezListy()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun poczekajNaOchlodzenie(pm: PowerManager) {
        var czekano = 0
        while (czekano < 600) {
            val stan = try {
                pm.currentThermalStatus
            } catch (e: Exception) {
                PowerManager.THERMAL_STATUS_NONE
            }
            if (stan < PowerManager.THERMAL_STATUS_SEVERE) return
            if (czekano == 0) {
                Stan.dopisz("Telefon się grzeje - czekam, aż ochłonie")
                powiadom("Przerwa na ochłodzenie", "Telefon się nagrzał")
            }
            delay(30_000)
            czekano += 30
            if (Stan.praca.value.stopTeraz || Stan.praca.value.stopPoPliku) return
        }
    }

    private fun zapiszWpis(
        baza: Baza, film: Film, status: String, powod: String,
        plikWyniku: String? = null, rozmiarWyniku: Long = 0
    ) {
        baza.zapisz(
            film.klucz, film.uri, film.nazwa, film.rozmiar, film.czasMs,
            film.sciezkaWzgledna, film.dataZrobienia, film.dataModyfikacji,
            film.szerokosc, film.wysokosc, film.obrot, film.maAudio,
            status, powod, plikWyniku, rozmiarWyniku
        )
    }

    // -------------------------------------------------- powiadomienie

    private fun startForegroundBezpiecznie(tytul: String, tresc: String) {
        val typ = if (Build.VERSION.SDK_INT >= 35) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        ServiceCompat.startForeground(this, ID_POWIADOMIENIA, zbuduj(tytul, tresc), typ)
    }

    private fun powiadom(tytul: String, tresc: String) {
        val m = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        m.notify(ID_POWIADOMIENIA, zbuduj(tytul, tresc))
    }

    private fun zbuduj(tytul: String, tresc: String): Notification {
        val m = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (m.getNotificationChannel(KANAL) == null) {
            m.createNotificationChannel(
                NotificationChannel(KANAL, "Kodowanie", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val otworz = PendingIntent.getActivity(
            this, 0, Intent(this, GlownaAktywnosc::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val poPliku = PendingIntent.getService(
            this, 1, Intent(this, UslugaKodowania::class.java).setAction(STOP_PO_PLIKU),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val teraz = PendingIntent.getService(
            this, 2, Intent(this, UslugaKodowania::class.java).setAction(STOP_TERAZ),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val s = Stan.praca.value
        return NotificationCompat.Builder(this, KANAL)
            .setContentTitle(tytul)
            .setContentText(tresc)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(otworz)
            .setProgress(100, if (s.razem > 0) (s.udzial * 100).toInt() else 0, s.razem == 0)
            .addAction(0, "Po bieżącym", poPliku)
            .addAction(0, "Przerwij", teraz)
            .build()
    }

    /**
     * Android 15 daje usludze najwyzej 6 godzin na dobe i wola ta metode,
     * gdy czas sie konczy. Trzeba zwolnic pole w kilka sekund, inaczej
     * system ubija aplikacje.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Stan.dopisz("System przerwał pracę po limicie czasu - reszta czeka")
        Stan.zmien { it.copy(stopPoPliku = true, stopTeraz = true) }
        zadaniePliku?.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        Stan.uslugaDziala = false
        zakres.cancel()
        super.onDestroy()
    }
}
