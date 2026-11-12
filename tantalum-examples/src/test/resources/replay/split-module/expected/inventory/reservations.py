"""Reserving stock."""
from inventory.stock import _levels


def reserve_stock(sku, quantity):
    """Take quantity out of stock; raises when there is not enough."""
    available = _levels.get(sku, 0)
    if quantity > available:
        raise ValueError("not enough stock for " + sku)
    _levels[sku] = available - quantity
    return _levels[sku]
