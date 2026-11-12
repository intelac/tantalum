"""Prices and discounts."""


def unit_total(quantity, unit_price):
    """Price of a line before any discount."""
    return quantity * unit_price


def apply_discount(price, percent):
    """Return the price after taking percent off."""
    if percent < 0 or percent > 100:
        raise ValueError("percent must be between 0 and 100")
    return price * (100 - percent) / 100
