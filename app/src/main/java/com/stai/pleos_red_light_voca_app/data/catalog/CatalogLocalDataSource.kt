package com.stai.pleos_red_light_voca_app.data.catalog

import android.content.Context
import com.stai.pleos_red_light_voca_app.core.common.LearningConfig
import com.stai.pleos_red_light_voca_app.domain.repository.CatalogResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** assets의 catalog.json을 읽어 파싱한다. */
class CatalogLocalDataSource(private val context: Context) {
    suspend fun load(): CatalogResult = withContext(Dispatchers.IO) {
        try {
            val text = context.assets.open(LearningConfig.CATALOG_ASSET_PATH).use {
                it.readBytes().toString(Charsets.UTF_8).removePrefix("\uFEFF")
            }
            CatalogParser.parse(text)
        } catch (e: IOException) {
            CatalogResult.Failure("catalog 읽기 실패: ${e.message}")
        }
    }
}
