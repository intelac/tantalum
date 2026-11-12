"""Shipping fees."""

BASE_FEE = 5  # flat part of every parcel
EXPRESS_SURCHARGE = 12
ZONE_RATES = {"local": 1.0, "national": 1.5, "overseas": 3.5}


def compute_shipping_fee(weight_kg, zone, express=False, *, currency):
    """Fee for one parcel. currency is required: 'CNY' or 'USD'."""
    if currency not in ("CNY", "USD"):
        raise ValueError("unknown currency: " + str(currency))
    if zone not in ZONE_RATES:
        raise ValueError("unknown zone: " + zone)
    fee = BASE_FEE + weight_kg * ZONE_RATES[zone]
    if express:
        fee += EXPRESS_SURCHARGE
    if currency == "USD":
        return round(fee / 7, 2)
    return fee
