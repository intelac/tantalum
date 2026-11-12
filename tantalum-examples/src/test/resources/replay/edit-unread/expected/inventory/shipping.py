"""Shipping fees."""

BASE_FEE = 6  # flat part of every parcel
EXPRESS_SURCHARGE = 12
ZONE_RATES = {"local": 1.0, "national": 1.5, "overseas": 3.5}


def compute_shipping_fee(weight_kg, zone, express=False):
    """Fee for one parcel."""
    if zone not in ZONE_RATES:
        raise ValueError("unknown zone: " + zone)
    fee = BASE_FEE + weight_kg * ZONE_RATES[zone]
    return fee + EXPRESS_SURCHARGE if express else fee
