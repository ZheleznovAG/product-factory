package catalog.service

import kotlinx.serialization.Serializable

@Serializable
data class CatalogItem(
    val id: String,
    val name: String,
    val priceCents: Int,
)

class CatalogService {
    fun listItems(): List<CatalogItem> = listOf(
        CatalogItem(id = "sku-1", name = "Notebook", priceCents = 499),
        CatalogItem(id = "sku-2", name = "Pen", priceCents = 199),
    )
}
