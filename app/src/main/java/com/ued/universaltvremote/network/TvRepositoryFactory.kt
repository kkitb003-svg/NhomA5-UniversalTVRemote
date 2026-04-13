package com.ued.universaltvremote.network

import android.content.Context
import com.ued.universaltvremote.model.TvBrand

object TvRepositoryFactory {
    fun create(brand: TvBrand, context: Context): TvRemoteRepository {
        return when (brand) {
            TvBrand.SAMSUNG -> SamsungRemoteRepository(context)
            TvBrand.LG -> LgWebOsRepository(context)
            TvBrand.ROKU -> RokuRemoteRepository()
            TvBrand.SONY -> SonyBraviaRepository()
            TvBrand.ANDROID_TV, TvBrand.TCL, TvBrand.XIAOMI, TvBrand.CASPER, TvBrand.PANASONIC, TvBrand.FIRE_TV -> AndroidTvRepository()
            TvBrand.VIZIO -> VizioRepository()
            TvBrand.VIDAA -> VidaaRepository()
            TvBrand.UNKNOWN -> SamsungRemoteRepository(context) // Fallback after probing
        }
    }
}
