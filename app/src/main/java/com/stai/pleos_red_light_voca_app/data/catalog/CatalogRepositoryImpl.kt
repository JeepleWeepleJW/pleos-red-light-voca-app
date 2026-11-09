package com.stai.pleos_red_light_voca_app.data.catalog

import com.stai.pleos_red_light_voca_app.domain.repository.CatalogResult
import com.stai.pleos_red_light_voca_app.domain.repository.CatalogRepository

class CatalogRepositoryImpl(private val local: CatalogLocalDataSource) : CatalogRepository {
    override suspend fun load(): CatalogResult = local.load()
}
