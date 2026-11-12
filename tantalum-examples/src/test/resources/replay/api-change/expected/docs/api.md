# API

- `inventory.pricing.apply_discount(price, percent)`
- `inventory.shipping.compute_shipping_fee(weight_kg, zone, express=False, *, currency)` — `currency` is required, `'CNY'` or `'USD'` (USD = CNY fee / 7, rounded to 2 decimals); other values raise `ValueError`
- `inventory.stock.reserve_stock(sku, quantity)`
- `inventory.orders.place_order(sku, quantity, unit_price, zone, discount_percent=0)`
