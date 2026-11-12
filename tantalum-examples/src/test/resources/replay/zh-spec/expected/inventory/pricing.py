"""Prices and discounts."""


def unit_total(quantity, unit_price):
    """Price of a line before any discount."""
    return quantity * unit_price


MEMBER_DISCOUNTS = {"普通": 0, "白银": 5, "黄金": 10}


def member_discount(price, level):
    """Return the price after the member-level discount, rounded to 2 places."""
    if level not in MEMBER_DISCOUNTS:
        raise ValueError("unknown member level: %r" % (level,))
    return round(price * (100 - MEMBER_DISCOUNTS[level]) / 100, 2)


def apply_discount(price, percent):
    """Return the price after taking percent off."""
    if percent < 0 or percent > 100:
        raise ValueError("percent must be between 0 and 100")
    return price * (100 - percent) / 100
