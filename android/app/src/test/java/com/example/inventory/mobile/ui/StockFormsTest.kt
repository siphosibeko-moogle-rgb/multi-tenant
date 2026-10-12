package com.example.inventory.mobile.ui

import com.example.inventory.mobile.ui.theme.MonochromeLight
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StockFormsTest {

    @Test
    fun `name and SKU are required`() {
        val (valid, errors) = validateProductForm(ProductForm())
        assertNull(valid)
        assertTrue("name" in errors)
        assertTrue("sku" in errors)
    }

    @Test
    fun `a minimal product passes and blank optional fields become null`() {
        val (valid, errors) = validateProductForm(ProductForm(name = " Tea ", sku = "TEA-1"))
        assertTrue(errors.isEmpty())
        assertEquals("Tea", valid!!.name)
        assertNull(valid.barcode)
        assertNull(valid.costPrice)
        assertNull(valid.openingStock)
    }

    @Test
    fun `negative and non-numeric prices are rejected`() {
        val (valid, errors) = validateProductForm(
            ProductForm(name = "Tea", sku = "TEA-1", costPrice = "-1", sellingPrice = "abc"),
        )
        assertNull(valid)
        assertEquals("Cannot be negative", errors["costPrice"])
        assertEquals("Enter a number", errors["sellingPrice"])
    }

    @Test
    fun `money keeps its decimals exactly`() {
        val (valid, _) = validateProductForm(ProductForm(name = "Tea", sku = "T", sellingPrice = "19.99"))
        assertEquals(BigDecimal("19.99"), valid!!.sellingPrice)
    }

    @Test
    fun `zero opening stock is treated as none`() {
        val (valid, _) = validateProductForm(ProductForm(name = "Tea", sku = "T", openingStock = "0"))
        assertNull(valid!!.openingStock)
    }

    @Test
    fun `positive opening stock is kept`() {
        val (valid, _) = validateProductForm(ProductForm(name = "Tea", sku = "T", openingStock = "24"))
        assertEquals(BigDecimal("24"), valid!!.openingStock)
    }

    @Test
    fun `add gives a positive delta and remove a negative one`() {
        assertEquals(BigDecimal("5"), buildAdjustmentDelta(AdjustDirection.ADD, "5", "delivery").first)
        assertEquals(BigDecimal("-5"), buildAdjustmentDelta(AdjustDirection.REMOVE, "5", "damaged").first)
    }

    @Test
    fun `zero, negative and empty quantities are refused`() {
        listOf("0", "-3", "", "x").forEach { qty ->
            val (delta, error) = buildAdjustmentDelta(AdjustDirection.ADD, qty, "delivery")
            assertNull("quantity '$qty' should be refused", delta)
            assertNotNull(error)
        }
    }

    @Test
    fun `a reason is required`() {
        val (delta, error) = buildAdjustmentDelta(AdjustDirection.REMOVE, "2", "  ")
        assertNull(delta)
        assertNotNull(error)
    }

    @Test
    fun `only owner and manager see catalogue and stock actions`() {
        assertTrue(canEditCatalog("owner"))
        assertTrue(canEditCatalog("MANAGER"))
        assertFalse(canEditCatalog("clerk"))
        assertFalse(canEditCatalog("viewer"))
        assertFalse(canEditCatalog(null))
    }

    @Test
    fun `every colour in the theme is a grey`() {
        val s = MonochromeLight
        val colours = listOf(
            s.primary, s.onPrimary, s.primaryContainer, s.secondary, s.tertiary,
            s.background, s.surface, s.surfaceVariant, s.onSurfaceVariant,
            s.error, s.errorContainer, s.outline, s.outlineVariant,
            s.surfaceContainer, s.surfaceContainerHigh, s.surfaceContainerHighest,
        )
        colours.forEach { c ->
            assertTrue("$c is not grey", c.red == c.green && c.green == c.blue)
        }
    }
}
