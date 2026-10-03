package nz.skull.vitalibre

import nz.skull.vitalibre.core.BPEstimator
import nz.skull.vitalibre.core.BPMargin

/**
 * What the screens say about blood pressure. The rules are in core (BPDisplay, BPMargin); this feeds them the
 * app's state. Android has no calibration gate: a figure is shown unless the pulse is irregular.
 */
object BPPresentation {
    /** The small print under a figure: how far it can be out. */
    fun margin(prefs: Prefs): String {
        val model = Publisher.model
        val base = BPEstimator.baseline(model, if (prefs.age > 0) prefs.age else null, prefs.sex, prefs.usual)
        return BPMargin.text(model, prefs.calibration, base, model.baseSystolic to model.baseDiastolic)
    }
}
