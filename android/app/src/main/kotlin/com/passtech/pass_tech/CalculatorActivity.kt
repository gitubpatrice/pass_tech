package com.passtech.pass_tech

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.GridLayout
import android.widget.TextView
import android.app.Activity
import java.text.DecimalFormat

/**
 * SEC F2 v2.5.4 — façade « Calculatrice » du mode panique.
 *
 * Avant, l'alias leurre visait `MainActivity` : toucher l'entrée « Calculatrice »
 * du lanceur ouvrait l'écran de déverrouillage Pass Tech. Le camouflage se
 * démentait au premier appui, ce qui est pire que pas de camouflage — un
 * adversaire qui vérifie voit qu'on a cherché à lui cacher quelque chose.
 *
 * Cette calculatrice est RÉELLE et fonctionnelle. Une façade inerte (boutons
 * sans effet, résultat toujours faux) serait tout aussi révélatrice.
 *
 * **Sortie de secours : appui long de 2 s sur l'affichage.**
 * Choix délibéré face à un code numérique fixe, qui cumulait deux défauts :
 * il s'oublie — et le propriétaire perd alors l'accès à son coffre, puisque le
 * bouton « Révéler » vit dans les Réglages de Pass Tech, devenus inatteignables
 * — et il est PUBLIC, le dépôt étant sous licence Apache 2.0. Un geste n'a rien
 * à mémoriser.
 *
 * ⚠️ LIMITE ASSUMÉE : ce geste est lui aussi public. Il protège d'une
 * inspection distraite, pas d'un adversaire qui sait déjà que l'appareil porte
 * Pass Tech. Dans ce cas le camouflage a de toute façon échoué en amont.
 */
class CalculatorActivity : Activity() {

    private lateinit var display: TextView

    /** Opérande gauche mémorisé, `null` tant qu'aucun opérateur n'a été saisi. */
    private var accumulator: Double? = null
    private var pendingOp: String? = null

    /** Vrai quand la prochaine touche chiffre doit REMPLACER l'affichage. */
    private var startNewOperand = true

    private val fmt = DecimalFormat("#.##########")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calculator)
        display = findViewById(R.id.display)

        display.setOnLongClickListener {
            revealPassTech()
            true
        }

        buildPad(findViewById(R.id.pad))
    }

    /**
     * Ouvre Pass Tech et termine la façade.
     *
     * On ne réactive PAS l'alias normal ici : ce serait faire réapparaître
     * l'icône Pass Tech sur le lanceur à l'insu de l'utilisateur, alors qu'il
     * vient peut-être seulement de consulter son coffre sous camouflage. La
     * révélation reste une action explicite, depuis les Réglages.
     */
    private fun revealPassTech() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
            )
            finish()
        } catch (_: Exception) {
            // Ne JAMAIS afficher d'erreur : un message d'échec sur une
            // calculatrice trahirait la présence d'autre chose.
        }
    }

    private val keys = listOf(
        "C", "±", "%", "÷",
        "7", "8", "9", "×",
        "4", "5", "6", "−",
        "1", "2", "3", "+",
        "0", ".", "⌫", "=",
    )

    private fun buildPad(pad: GridLayout) {
        pad.removeAllViews()
        keys.forEach { k ->
            val b = Button(this).apply {
                text = k
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                isAllCaps = false
                setBackgroundColor(
                    when (k) {
                        "=" -> Color.parseColor("#FF8A65")
                        "÷", "×", "−", "+" -> Color.parseColor("#ECEFF1")
                        else -> Color.WHITE
                    }
                )
                setTextColor(
                    if (k == "=") Color.WHITE else Color.parseColor("#212121")
                )
                setOnClickListener { onKey(k) }
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = 0
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(6, 6, 6, 6)
                }
                gravity = Gravity.CENTER
            }
            pad.addView(b)
        }
    }

    private fun current(): Double = display.text.toString().toDoubleOrNull() ?: 0.0

    private fun show(v: Double) {
        display.text = if (v.isNaN() || v.isInfinite()) "Erreur" else fmt.format(v)
    }

    private fun onKey(k: String) {
        when (k) {
            "C" -> {
                accumulator = null
                pendingOp = null
                startNewOperand = true
                display.text = "0"
            }

            "⌫" -> {
                val s = display.text.toString()
                display.text = if (s.length <= 1) "0" else s.dropLast(1)
                if (display.text == "-") display.text = "0"
            }

            "±" -> show(-current())

            "%" -> show(current() / 100.0)

            "÷", "×", "−", "+" -> {
                // Chaînage : « 2 + 3 + » doit afficher 5 avant de poursuivre.
                applyPending()
                pendingOp = k
                startNewOperand = true
            }

            "=" -> {
                applyPending()
                pendingOp = null
                startNewOperand = true
            }

            "." -> {
                if (startNewOperand) {
                    display.text = "0."
                    startNewOperand = false
                } else if (!display.text.contains('.')) {
                    display.text = "${display.text}."
                }
            }

            else -> { // chiffres
                if (startNewOperand || display.text == "0") {
                    display.text = k
                    startNewOperand = false
                } else {
                    display.text = "${display.text}$k"
                }
            }
        }
    }

    /** Applique l'opérateur en attente, s'il y en a un. */
    private fun applyPending() {
        val a = accumulator
        val op = pendingOp
        val b = current()
        if (a == null || op == null) {
            accumulator = b
            return
        }
        val r = when (op) {
            "+" -> a + b
            "−" -> a - b
            "×" -> a * b
            // Division par zéro : `Double` rend Infinity, que `show` affiche
            // « Erreur ». Pas d'exception, donc pas de plantage révélateur.
            "÷" -> a / b
            else -> b
        }
        accumulator = r
        show(r)
    }
}
