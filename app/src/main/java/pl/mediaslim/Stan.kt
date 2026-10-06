package pl.mediaslim

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Co teraz robi aplikacja - jedno zrodlo prawdy dla ekranu i uslugi. */
data class StanPracy(
    val trwa: Boolean = false,
    val etykieta: String = "",
    val plik: String = "",
    val numer: Int = 0,
    val razem: Int = 0,
    val procentPliku: Int = 0,
    val sekundyZrobione: Double = 0.0,
    val sekundyRazem: Double = 0.0,
    val poczatek: Long = 0L,
    val odzyskane: Long = 0L,
    val stopPoPliku: Boolean = false,
    val stopTeraz: Boolean = false,
    val log: List<String> = emptyList()
) {
    val udzial: Double
        get() = if (sekundyRazem > 0) (sekundyZrobione / sekundyRazem).coerceIn(0.0, 1.0) else 0.0

    val uplynelo: Long
        get() = if (poczatek > 0) (System.currentTimeMillis() - poczatek) / 1000 else 0

    val pozostalo: Long
        get() {
            val u = udzial
            return if (u > 0.01 && uplynelo > 5) ((uplynelo / u) - uplynelo).toLong() else 0
        }
}

object Stan {
    private val _praca = MutableStateFlow(StanPracy())
    val praca: StateFlow<StanPracy> = _praca

    private val _kandydaci = MutableStateFlow<List<Film>>(emptyList())
    val kandydaci: StateFlow<List<Film>> = _kandydaci

    private val _zaznaczone = MutableStateFlow<Set<String>>(emptySet())
    val zaznaczone: StateFlow<Set<String>> = _zaznaczone

    private val _podsumowanieSkanu = MutableStateFlow<Map<String, Int>>(emptyMap())
    val podsumowanieSkanu: StateFlow<Map<String, Int>> = _podsumowanieSkanu

    private val _odswiez = MutableStateFlow(0)
    val odswiez: StateFlow<Int> = _odswiez

    /** "wideo" albo "zdjecia" - co skanujemy i kodujemy. */
    private val _tryb = MutableStateFlow("wideo")
    val tryb: StateFlow<String> = _tryb

    /** Kolejka przekazywana do uslugi kodowania. */
    @Volatile
    var kolejka: List<Film> = emptyList()

    /** Czy usluga kodowania faktycznie pracuje - UI nie budzi martwej uslugi. */
    @Volatile
    var uslugaDziala: Boolean = false

    fun ustawTryb(nowy: String) {
        if (_tryb.value == nowy) return
        _tryb.value = nowy
        // Lista z poprzedniego trybu nie ma tu nic do roboty.
        _kandydaci.value = emptyList()
        _zaznaczone.value = emptySet()
        _podsumowanieSkanu.value = emptyMap()
    }

    fun ustawKandydatow(lista: List<Film>, pominiete: Map<String, Int>) {
        _kandydaci.value = lista
        _zaznaczone.value = lista.map { it.klucz }.toSet()
        _podsumowanieSkanu.value = pominiete
    }

    fun przelacz(klucz: String) {
        val teraz = _zaznaczone.value.toMutableSet()
        if (!teraz.remove(klucz)) teraz.add(klucz)
        _zaznaczone.value = teraz
    }

    fun zaznaczWszystkie(tak: Boolean) {
        _zaznaczone.value = if (tak) _kandydaci.value.map { it.klucz }.toSet() else emptySet()
    }

    fun zmien(blok: (StanPracy) -> StanPracy) {
        _praca.value = blok(_praca.value)
    }

    fun dopisz(linia: String) {
        zmien { it.copy(log = (it.log + linia).takeLast(200)) }
    }

    fun odswiezListy() {
        _odswiez.value = _odswiez.value + 1
    }

    fun wyczyscPoPracy() {
        zmien {
            it.copy(
                trwa = false, plik = "", procentPliku = 0,
                stopPoPliku = false, stopTeraz = false
            )
        }
    }
}
