"""Prices and discounts."""


def unit_total(quantity, unit_price):
    """Price of a line before any discount is applied."""
    return quantity * unit_price


def apply_discount(price, percent):
    """Return the price after taking percent off."""
    if percent < 0 or percent > 100:
        raise ValueError("percent must be between 0 and 100")
    return price * (100 - percent) / 100


def bulk_discount(price, quantity):
    """Return the price after the quantity discount.

    Under 10 items: no discount; 10-49: 5% off; 50 or more: 10% off.
    """
    if quantity >= 50:
        return apply_discount(price, 10)
    if quantity >= 10:
        return apply_discount(price, 5)
    return price
