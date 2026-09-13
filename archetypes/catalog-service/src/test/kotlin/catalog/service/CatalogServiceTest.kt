package catalog.service

import kotlin.test.Test
import kotlin.test.assertEquals

class CatalogServiceTest {
    @Test
    fun `returns seeded catalog items`() {
        val items = CatalogService().listItems()

        assertEquals(2, items.size)
        assertEquals("sku-1", items.first().id)
    }
}
