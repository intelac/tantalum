"""Stock levels kept in memory (not persisted)."""
from inventory.data.catalog import load_item


class StockBook:
    """Keeps the in-memory stock level of each SKU."""

    def __init__(self):
        self._levels = {}

    def restock(self, sku, quantity):
        load_item(sku)
        self._levels[sku] = self._levels.get(sku, 0) + quantity
        return self._levels[sku]

    def reserve(self, sku, quantity):
        """Take quantity out of stock; raises when there is not enough."""
        available = self._levels.get(sku, 0)
        if quantity > available:
            raise ValueError("not enough stock for " + sku)
        self._levels[sku] = available - quantity
        return self._levels[sku]


_book = StockBook()


def restock(sku, quantity):
    return _book.restock(sku, quantity)


def reserve_stock(sku, quantity):
    """Take quantity out of stock; raises when there is not enough."""
    return _book.reserve(sku, quantity)
