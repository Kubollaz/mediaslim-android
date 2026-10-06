package pl.mediaslim

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GlownaAktywnosc : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Aplikacja() }
    }
}

private fun potrzebneUprawnienia(): Array<String> {
    val lista = ArrayList<String>()
    if (Build.VERSION.SDK_INT >= 33) {
        lista.add(Manifest.permission.READ_MEDIA_VIDEO)
        lista.add(Manifest.permission.READ_MEDIA_IMAGES)
        lista.add(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        lista.add(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    return lista.toTypedArray()
}

private fun maDostepDoFilmow(ctx: Context): Boolean {
    val potrzebne = if (Build.VERSION.SDK_INT >= 33) {
        listOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_IMAGES)
    } else {
        listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    return potrzebne.all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }
}

@Composable
fun Aplikacja() {
    val ciemny = isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (ciemny) darkColorScheme() else lightColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Ekran()
        }
    }
}

@Composable
private fun Ekran() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val zakres = rememberCoroutineScope()
    val baza = remember { Baza(ctx) }

    val praca by Stan.praca.collectAsState()
    val kandydaci by Stan.kandydaci.collectAsState()
    val zaznaczone by Stan.zaznaczone.collectAsState()
    val pominiete by Stan.podsumowanieSkanu.collectAsState()
    val odswiez by Stan.odswiez.collectAsState()
    val tryb by Stan.tryb.collectAsState()

    var zakladka by remember { mutableStateOf(0) }
    var maDostep by remember { mutableStateOf(maDostepDoFilmow(ctx)) }
    var profil by remember { mutableStateOf(Ustawienia.profil(ctx)) }
    var gotowe by remember { mutableStateOf<List<Gotowy>>(emptyList()) }
    var wKoszu by remember { mutableStateOf<List<WKoszu>>(emptyList()) }
    var pary by remember { mutableStateOf<List<Podmiana.Para>>(emptyList()) }
    var komunikat by remember { mutableStateOf("") }

    val pytanieOUprawnienia = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { maDostep = maDostepDoFilmow(ctx) }

    val pytanieOKosz = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { wynik ->
        val doDokonczenia = pary
        pary = emptyList()
        zakres.launch {
            if (wynik.resultCode == Activity.RESULT_OK) {
                val ile = withContext(Dispatchers.IO) {
                    Podmiana.dokoncz(ctx, doDokonczenia, baza)
                }
                komunikat = "Podmienione: $ile. Oryginały są w koszu - miejsce " +
                    "odzyskasz po jego opróżnieniu."
            } else {
                // Uzytkownik odmowil - sprzatamy wstawione, jeszcze ukryte kopie
                withContext(Dispatchers.IO) {
                    doDokonczenia.forEach {
                        try {
                            ctx.contentResolver.delete(it.nowyUri, null, null)
                        } catch (e: Exception) {
                            // nic
                        }
                    }
                }
                komunikat = "Anulowane - nic nie zostało zmienione."
            }
            Stan.odswiezListy()
        }
    }

    val pytanieOKoszOperacje = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { Stan.odswiezListy() }

    // Systemowe zadanie na kosz potrafi NIE zwrocic bledu, tylko rzucic
    // wyjatkiem prosto z dostawcy MediaStore - na adresie, ktorego juz
    // nie ma w galerii. Bez tego jedno nieaktualne wejscie wywalalo
    // aplikacje przy "Opróżnij".
    fun wykonajKoszowe(zadanie: Podmiana.Zadanie, czynnosc: String) {
        if (zadanie.martwe.isNotEmpty()) {
            val doZapomnienia = wKoszu
            zakres.launch {
                withContext(Dispatchers.IO) {
                    Podmiana.zapomnijMartwe(baza, doZapomnienia, zadanie.martwe)
                }
                Stan.odswiezListy()
            }
        }
        val pi = zadanie.intent
        val oMartwych =
            if (zadanie.martwe.isEmpty()) ""
            else " Z listy zniknęło ${zadanie.martwe.size} pozycji, " +
                "których nie ma już w galerii."
        if (pi == null) {
            komunikat = "Nie udało się $czynnosc: ${zadanie.blad ?: "brak powodu"}.$oMartwych"
            return
        }
        try {
            pytanieOKoszOperacje.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            if (oMartwych.isNotEmpty()) komunikat = oMartwych.trim()
        } catch (e: Exception) {
            komunikat = "System odrzucił żądanie: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    LaunchedEffect(odswiez) {
        val (g, k) = withContext(Dispatchers.IO) {
            Pair(baza.oStatusie("zakodowany"), baza.wKoszu())
        }
        gotowe = g
        wKoszu = k
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("MediaSlim", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "  wideo 0.1",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (praca.trwa) KartaPostepu(praca, ctx)

        if (komunikat.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(komunikat, fontSize = 13.sp)
                    Text(
                        "ukryj",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { komunikat = "" }.padding(top = 6.dp)
                    )
                }
            }
        }

        if (!maDostep) {
            Column(modifier = Modifier.padding(vertical = 24.dp)) {
                Text(
                    "Aplikacja potrzebuje dostępu do filmów w galerii. " +
                        "Wybierz „Zezwól na wszystkie” - przy dostępie do wybranych " +
                        "plików nie da się przeszukać archiwum.",
                    fontSize = 14.sp
                )
                Button(
                    onClick = { pytanieOUprawnienia.launch(potrzebneUprawnienia()) },
                    modifier = Modifier.padding(top = 12.dp)
                ) { Text("Daj dostęp do filmów") }
            }
            return@Column
        }

        TabRow(selectedTabIndex = zakladka) {
            Tab(
                selected = zakladka == 0,
                onClick = { zakladka = 0 },
                text = { Text("Skan") })
            Tab(
                selected = zakladka == 1,
                onClick = { zakladka = 1 },
                text = { Text("Gotowe (${gotowe.size})") })
            Tab(
                selected = zakladka == 2,
                onClick = { zakladka = 2 },
                text = { Text("Kosz (${wKoszu.size})") })
        }

        when (zakladka) {
            0 -> ZakladkaSkan(
                tryb = tryb,
                zmienTryb = { Stan.ustawTryb(it) },
                profil = profil,
                zmienProfil = { nowy ->
                    Ustawienia.ustawProfil(ctx, nowy.id)
                    profil = nowy
                },
                kandydaci = kandydaci,
                zaznaczone = zaznaczone,
                pominiete = pominiete,
                trwa = praca.trwa,
                skanuj = {
                    zakres.launch {
                        Stan.zmien {
                            it.copy(
                                trwa = true, razem = 0,
                            etykieta = if (tryb == "zdjecia") "Szukam zdjęć"
                                       else "Szukam filmów",
                                numer = 0, poczatek = System.currentTimeMillis(),
                                sekundyRazem = 0.0, sekundyZrobione = 0.0, odzyskane = 0
                            )
                        }
                        val wynik = withContext(Dispatchers.IO) {
                            val raport: (Int, String) -> Unit = { n, nazwa ->
                                Stan.zmien { it.copy(numer = n, plik = nazwa) }
                            }
                            val stop: () -> Boolean = {
                                Stan.praca.value.stopTeraz || Stan.praca.value.stopPoPliku
                            }
                            if (tryb == "zdjecia") {
                                Skaner.skanujZdjecia(ctx, baza, profil, raport, stop)
                            } else {
                                Skaner.skanuj(ctx, baza, profil, raport, stop)
                            }
                        }
                        Stan.ustawKandydatow(wynik.kandydaci, wynik.pominiete)
                        Stan.wyczyscPoPracy()
                        komunikat = "Obejrzane: ${wynik.obejrzane}. " +
                            "Do przerobienia: ${wynik.kandydaci.size} " +
                            "(${waga(wynik.razem)}), szacowany zysk " +
                            waga(wynik.doOdzyskania) + "."
                    }
                },
                koduj = {
                    val wybrane = kandydaci.filter { zaznaczone.contains(it.klucz) }
                    if (wybrane.isEmpty()) {
                        komunikat = "Nic nie zaznaczono."
                    } else {
                        Stan.kolejka = wybrane
                        UslugaKodowania.uruchom(ctx)
                        komunikat = "Kodowanie ruszyło. Możesz zgasić ekran - " +
                            "postęp jest w powiadomieniu."
                    }
                }
            )

            1 -> ZakladkaGotowe(
                gotowe = gotowe,
                podmien = {
                    zakres.launch {
                        Stan.zmien {
                            it.copy(trwa = true, etykieta = "Przygotowanie kopii", razem = gotowe.size)
                        }
                        val przygotowane = withContext(Dispatchers.IO) {
                            Podmiana.przygotuj(ctx, gotowe) { n, nazwa ->
                                Stan.zmien { it.copy(numer = n, plik = nazwa) }
                            }
                        }
                        Stan.wyczyscPoPracy()
                        if (przygotowane.isEmpty()) {
                            komunikat = "Nie udało się przygotować kopii."
                        } else {
                            val zadanie = Podmiana.zadanieKosza(ctx, przygotowane)
                            val pi = zadanie.intent
                            if (pi == null) {
                                pary = emptyList()
                                withContext(Dispatchers.IO) {
                                    przygotowane.forEach {
                                        try {
                                            ctx.contentResolver.delete(it.nowyUri, null, null)
                                        } catch (e: Exception) {
                                            // nic
                                        }
                                    }
                                }
                                komunikat = "Nie udało się przenieść oryginałów do kosza: " +
                                    (zadanie.blad ?: "brak powodu") + ". Nic nie zmieniono."
                            } else {
                                pary = przygotowane
                                try {
                                    pytanieOKosz.launch(
                                        IntentSenderRequest.Builder(pi.intentSender).build()
                                    )
                                } catch (e: Exception) {
                                    pary = emptyList()
                                    komunikat = "System odrzucił żądanie: " +
                                        (e.message ?: e.javaClass.simpleName)
                                }
                            }
                        }
                    }
                }
            )

            2 -> ZakladkaKosz(
                wKoszu = wKoszu,
                przywroc = {
                    if (wKoszu.isNotEmpty()) {
                        wykonajKoszowe(
                            Podmiana.zadaniePrzywrocenia(ctx, wKoszu), "przywrócić"
                        )
                    }
                },
                oproznij = {
                    if (wKoszu.isNotEmpty()) {
                        wykonajKoszowe(
                            Podmiana.zadanieKasowania(ctx, wKoszu), "opróżnić kosza"
                        )
                    }
                }
            )
        }
    }
}

@Composable
private fun KartaPostepu(praca: StanPracy, ctx: Context) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            val etykieta = praca.etykieta +
                when {
                    praca.stopTeraz -> " — przerywam…"
                    praca.stopPoPliku -> " — kończę bieżący plik…"
                    else -> ""
                }
            Text(etykieta, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            if (praca.razem > 0) {
                LinearProgressIndicator(
                    progress = { praca.udzial.toFloat() },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                )
                Text(
                    "${praca.numer}/${praca.razem} · plik ${praca.procentPliku}% · " +
                        "upłynęło ${czasTekst(praca.uplynelo)} · " +
                        "zostało ${czasTekst(praca.pozostalo)}",
                    fontSize = 12.sp
                )
            } else if (praca.numer > 0) {
                Text("sprawdzono ${praca.numer} plików", fontSize = 12.sp)
            }
            if (praca.plik.isNotEmpty()) {
                Text(praca.plik, fontSize = 12.sp, maxLines = 1)
            }
            if (praca.odzyskane > 0) {
                Text("odzyskane ${waga(praca.odzyskane)}", fontSize = 12.sp)
            }
            Row(modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(
                    onClick = {
                        Stan.zmien { it.copy(stopPoPliku = true) }
                        if (Stan.uslugaDziala) {
                            UslugaKodowania.polecenie(ctx, UslugaKodowania.STOP_PO_PLIKU)
                        }
                    },
                    enabled = !praca.stopPoPliku
                ) { Text("Zatrzymaj po bieżącym", fontSize = 13.sp) }
                Box(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        Stan.zmien { it.copy(stopTeraz = true, stopPoPliku = true) }
                        if (Stan.uslugaDziala) {
                            UslugaKodowania.polecenie(ctx, UslugaKodowania.STOP_TERAZ)
                        }
                    },
                    enabled = !praca.stopTeraz
                ) { Text("Przerwij teraz", fontSize = 13.sp) }
            }
        }
    }
}

@Composable
private fun ZakladkaSkan(
    tryb: String,
    zmienTryb: (String) -> Unit,
    profil: Profil,
    zmienProfil: (Profil) -> Unit,
    kandydaci: List<Film>,
    zaznaczone: Set<String>,
    pominiete: Map<String, Int>,
    trwa: Boolean,
    skanuj: () -> Unit,
    koduj: () -> Unit
) {
    val zysk = kandydaci.filter { zaznaczone.contains(it.klucz) }.sumOf { it.zysk }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Column(modifier = Modifier.padding(vertical = 10.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    listOf("wideo" to "Filmy", "zdjecia" to "Zdjęcia").forEach { (id, opis) ->
                        val wybrany = tryb == id
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 6.dp)
                                .clickable(enabled = !trwa) { zmienTryb(id) },
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (wybrany) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                }
                            )
                        ) {
                            Text(
                                opis,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                                textAlign = TextAlign.Center,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (wybrany) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Text("Jak mocno ściskać", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Ustawienia.PROFILE.forEach { p ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .clickable { zmienProfil(p) },
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (p.id == profil.id) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(p.nazwa, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            Text(p.opis, fontSize = 12.sp)
                            Text(
                                "dłuższy bok do ${p.maxDluzszyBok} px · od ${p.minMB} MB",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Row(modifier = Modifier.padding(top = 10.dp)) {
                    Button(onClick = skanuj, enabled = !trwa) { Text("Skanuj galerię") }
                }
                if (kandydaci.isNotEmpty()) {
                    Row(modifier = Modifier.padding(top = 8.dp)) {
                        OutlinedButton(onClick = { Stan.zaznaczWszystkie(true) }) {
                            Text("Zaznacz wszystkie", fontSize = 13.sp)
                        }
                        Box(modifier = Modifier.width(8.dp))
                        OutlinedButton(onClick = { Stan.zaznaczWszystkie(false) }) {
                            Text("Odznacz wszystkie", fontSize = 13.sp)
                        }
                    }
                    Text(
                        "Zaznaczone ${zaznaczone.size} z ${kandydaci.size}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                if (pominiete.isNotEmpty()) {
                    Text(
                        "Pominięte: " + pominiete.entries.joinToString(" · ") {
                            "${it.key} ${it.value}"
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }

        items(kandydaci) { film ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { Stan.przelacz(film.klucz) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = zaznaczone.contains(film.klucz),
                    onCheckedChange = { Stan.przelacz(film.klucz) }
                )
                Column(modifier = Modifier.padding(start = 4.dp)) {
                    Text(film.nazwa, fontSize = 13.sp, maxLines = 1)
                    Text(
                        if (film.czyZdjecie) {
                            "${waga(film.rozmiar)} → ok. ${waga(film.szacowanyRozmiar)} · " +
                                "${film.pokazanaSzer}x${film.pokazanaWys} → " +
                                "${film.docelowaSzer}x${film.docelowaWys}"
                        } else {
                            "${waga(film.rozmiar)} → ok. ${waga(film.szacowanyRozmiar)} · " +
                                "${film.szerokosc}x${film.wysokosc} · " +
                                "${Math.round(film.fps)} kl/s · " +
                                String.format(java.util.Locale.US, "%.1f", film.mbps) + " Mb/s"
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            if (kandydaci.isNotEmpty()) {
                Button(
                    onClick = koduj,
                    enabled = !trwa && zaznaczone.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp)
                ) {
                    Text(
                        (if (tryb == "zdjecia") "Przerób zaznaczone" else "Koduj zaznaczone") +
                            " (${zaznaczone.size}) — zysk ok. ${waga(zysk)}"
                    )
                }
            }
        }
    }
}

@Composable
private fun ZakladkaGotowe(gotowe: List<Gotowy>, podmien: () -> Unit) {
    val zysk = gotowe.sumOf { it.rozmiar - it.rozmiarWyniku }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Column(modifier = Modifier.padding(vertical = 10.dp)) {
                if (gotowe.isEmpty()) {
                    Text("Nic nie czeka na podmianę.", fontSize = 14.sp)
                } else {
                    Text(
                        "Każdy z tych plików przeszedł komplet testów: jest mniejszy, " +
                            "nie leży na boku, nie jest zniekształcony, a filmy " +
                            "dodatkowo mają tę samą długość i ścieżkę dźwięku.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = podmien,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                    ) {
                        Text("Podmień ${gotowe.size} plików — zwolni ${waga(zysk)}")
                    }
                    Text(
                        "Oryginały trafią do kosza systemowego na 30 dni. " +
                            "Miejsce odzyskasz po opróżnieniu kosza w zakładce obok.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
        items(gotowe) { g ->
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(g.nazwa, fontSize = 13.sp, maxLines = 1)
                Text(
                    "${waga(g.rozmiar)} → ${waga(g.rozmiarWyniku)} (${g.procent}% oryginału)",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ZakladkaKosz(
    wKoszu: List<WKoszu>,
    przywroc: () -> Unit,
    oproznij: () -> Unit
) {
    val razem = wKoszu.sumOf { it.rozmiar }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Column(modifier = Modifier.padding(vertical = 10.dp)) {
                if (wKoszu.isEmpty()) {
                    Text("Kosz jest pusty.", fontSize = 14.sp)
                } else {
                    Text(
                        "Oryginały ${wKoszu.size} podmienionych filmów. " +
                            "Zajmują ${waga(razem)} - dopiero ich skasowanie " +
                            "zwalnia miejsce.",
                        fontSize = 13.sp
                    )
                    Row(modifier = Modifier.padding(top = 10.dp)) {
                        OutlinedButton(onClick = przywroc) { Text("Przywróć wszystkie") }
                        Box(modifier = Modifier.width(8.dp))
                        Button(onClick = oproznij) { Text("Opróżnij — zwolni ${waga(razem)}") }
                    }
                }
            }
        }
        items(wKoszu) { k ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(k.nazwa, fontSize = 13.sp, maxLines = 1)
                Text(waga(k.rozmiar), fontSize = 12.sp)
            }
        }
    }
}
