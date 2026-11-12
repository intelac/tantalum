"""Stock levels kept in memory (not persisted)."""
from inventory.data.catalog import load_item

_levels = {}


def restock(sku, quantity):
    load_item(sku)
    _levels[sku] = _levels.get(sku, 0) + quantity
    return _levels[sku]
