package io.github.panuwattegif.readyproof

import android.content.Context
import android.content.SharedPreferences
import io.github.panuwattegif.readyproof.core.Config
import io.github.panuwattegif.readyproof.core.ConfigCodec

/** The whole config is one JSON string, so it is always saved or restored as a unit. */
object ConfigStore {
    private const val PREFS = "readyproof"
    private const val KEY = "config"

    @Volatile
    private var cached: Config? = null

    fun get(ctx: Context): Config = cached ?: synchronized(this) {
        cached ?: ConfigCodec.decodeOrDefault(prefs(ctx).getString(KEY, null)).also { cached = it }
    }

    /** Returns problems to show the user; saves and tells the running service only when there are none. */
    fun save(ctx: Context, cfg: Config): List<String> {
        val errors = cfg.validate()
        if (errors.isNotEmpty()) return errors
        prefs(ctx).edit().putString(KEY, ConfigCodec.encode(cfg)).apply()
        cached = cfg
        ProofService.instance?.onConfigChanged(cfg)
        return emptyList()
    }

    fun update(ctx: Context, change: (Config) -> Config): List<String> = save(ctx, change(get(ctx)))

    fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
