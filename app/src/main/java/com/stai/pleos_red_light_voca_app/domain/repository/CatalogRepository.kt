package com.stai.pleos_red_light_voca_app.domain.repository

import com.stai.pleos_red_light_voca_app.core.entity.Catalog

sealed interface CatalogResult {
    data class Success(val catalog: Catalog) : CatalogResult
    data class Failure(val reason: String) : CatalogResult
}

interface CatalogRepository {
    suspend fun load(): CatalogResult
}
