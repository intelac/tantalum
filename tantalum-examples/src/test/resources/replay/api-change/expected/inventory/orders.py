"""Placing an order."""
from inventory.data.catalog import load_item
from inventory.pricing import apply_discount, unit_total
from inventory.shipping import compute_shipping_fee
from inventory.stock import reserve_stock


def place_order(sku, quantity, unit_price, zone, discount_percent=0):
    item = load_item(sku)
    reserve_stock(sku, quantity)
    goods = apply_discount(unit_total(quantity, unit_price), discount_percent)
    shipping = compute_shipping_fee(item["weight_kg"] * quantity, zone, currency="CNY")
    return {"sku": sku, "goods": goods, "shipping": shipping, "total": goods + shipping}
