package io.github.panuwattegif.readyproof

import android.content.Context
import io.github.panuwattegif.readyproof.core.Config

/** No automatic permanent deletion: evidence must remain available for manual handoff. */
object Cleanup {
    fun runIfDue(ctx: Context, cfg: Config) = Unit
}
