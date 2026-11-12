# API

- `inventory.pricing.apply_discount(price, percent)`
- `inventory.shipping.compute_shipping_fee(weight_kg, zone, express=False)`
- `inventory.reservations.reserve_stock(sku, quantity)`
- `inventory.orders.place_order(sku, quantity, unit_price, zone, discount_percent=0)`
