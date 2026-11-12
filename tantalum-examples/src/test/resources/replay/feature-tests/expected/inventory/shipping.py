"""Shipping fees."""

BASE_FEE = 5  # flat part of every parcel
EXPRESS_SURCHARGE = 12
ZONE_RATES = {"local": 1.0, "national": 1.5, "overseas": 3.5}


def compute_shipping_fee(weight_kg, zone, express=False):
    """Fee for one parcel."""
    if zone not in ZONE_RATES:
        raise ValueError("unknown zone: " + zone)
    fee = BASE_FEE + weight_kg * ZONE_RATES[zone]
    return fee + EXPRESS_SURCHARGE if express else fee


def split_fee(weight_kg, zone, batches):
    """Total fee when the weight is split evenly into `batches` parcels."""
    if batches < 1:
        raise ValueError("batches must be >= 1")
    per_batch = weight_kg / batches
    return sum(compute_shipping_fee(per_batch, zone) for _ in range(batches))
