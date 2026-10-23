package com.example.data.local

import kotlin.math.abs

/**
 * La version 1.0.1 injectait au premier lancement 4 paires et 4 sorties fictives, et cochait
 * tout le matos comme « possédé ». Ces règles repèrent ces données démo pour les retirer
 * une seule fois, sans toucher à ce que l'utilisateur a réellement saisi ou utilisé.
 */
object DemoDataCleanup {

    private data class DemoRunSignature(val locationName: String, val notes: String)
    private data class DemoShoeSignature(val name: String, val seededKm: Double)

    private val demoRuns = listOf(
        DemoRunSignature("Chamonix-Mont-Blanc", "Sentiers gras en sous-bois, accroche crampons parfaite."),
        DemoRunSignature("Annecy", "Tour du lac fluide."),
        DemoRunSignature("Grenoble - Chartreuse", "Passage de crête venté et pluvieux."),
        DemoRunSignature("Lyon - Parc de la Tête d'Or", "Séance VMA au soleil.")
    )

    private val demoShoes = listOf(
        DemoShoeSignature("Salomon S/Lab Genesis", 240.0),
        DemoShoeSignature("Salomon Speedcross 6 Mud", 185.0),
        DemoShoeSignature("Hoka Speedgoat 5", 648.0),
        DemoShoeSignature("Nike Pegasus 41 Road", 310.0)
    )

    /** Codes du matos par défaut (liste conservée, mais décochée pour une nouvelle installation). */
    val defaultGearCodes = listOf(
        "debardeur", "haut_court", "manchettes", "haut_long", "veste_pluie", "coupe_vent",
        "short", "cuissard_court", "cuissard_long", "chaussettes", "gants", "bonnet", "tour_de_cou",
        "guetres", "batons_trail", "frontale", "lunettes_soleil", "casquette", "creme_solaire",
        "gilet_hydratation", "gels"
    )

    fun isDemoRun(run: RunHistoryEntity): Boolean =
        demoRuns.any { it.locationName == run.locationName && it.notes == run.notes }

    /**
     * Paires démo jamais utilisées : même nom, km inchangés depuis l'injection,
     * et aucune vraie sortie (hors sorties démo) qui y fait référence.
     */
    fun unusedDemoShoes(shoes: List<ShoeEntity>, history: List<RunHistoryEntity>): List<ShoeEntity> {
        val usedShoeIds = history.filterNot { isDemoRun(it) }.mapNotNull { it.shoeId }.toSet()
        return shoes.filter { shoe ->
            shoe.id !in usedShoeIds && demoShoes.any { demo ->
                demo.name == shoe.name && abs(demo.seededKm - shoe.currentKm) < 0.01
            }
        }
    }

    /**
     * Vrai si le matos par défaut est encore dans son état injecté (tout coché) :
     * l'utilisateur n'y a pas touché, on peut tout décocher sans perdre de choix.
     */
    fun shouldUncheckDefaultGear(gear: List<GearItemEntity>): Boolean {
        val defaults = gear.filter { it.code in defaultGearCodes }
        return defaults.size == defaultGearCodes.size && defaults.all { it.owned }
    }
}
