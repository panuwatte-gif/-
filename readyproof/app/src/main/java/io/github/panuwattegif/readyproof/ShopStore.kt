package io.github.panuwattegif.readyproof

import android.content.Context
import io.github.panuwattegif.readyproof.core.Shop

/** Dedicated proof phones are bound once. Config reset does not change identity or old records. */
object ShopStore {
    fun get(ctx: Context): Shop? = Shop.fromId(ConfigStore.prefs(ctx).getString("shop_id", null))

    @Synchronized fun bind(ctx: Context, shop: Shop): Boolean {
        val existing = get(ctx)
        if (existing != null) return existing == shop
        return ConfigStore.prefs(ctx).edit().putString("shop_id", shop.id).commit()
    }

    fun reportRecords(ctx: Context, records: List<io.github.panuwattegif.readyproof.core.Record>) =
        records.filter { it.shopId == get(ctx)?.id }
}
