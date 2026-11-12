"""Shipping fees."""

BASE_FEE = 5  # flat part of every parcel
EXPRESS_SURCHARGE = 12
ZONE_RATES = {"local": 1.0, "national": 1.5, "overseas": 3.5}


def compute_shipping_fee(weight_kg, zone, express=False):
    """计算单个包裹的运费。

    参数:
        weight_kg: 包裹重量（千克）。
        zone: 配送区域，必须是 ZONE_RATES 中的键（local、national、overseas）。
        express: 是否加急，为 True 时加收加急附加费。

    返回:
        基础费用加上按区域费率计算的重量费用（加急时再加附加费）。

    异常:
        ValueError: 区域未知时抛出。
    """
    if zone not in ZONE_RATES:
        raise ValueError("unknown zone: " + zone)
    fee = BASE_FEE + weight_kg * ZONE_RATES[zone]
    return fee + EXPRESS_SURCHARGE if express else fee
