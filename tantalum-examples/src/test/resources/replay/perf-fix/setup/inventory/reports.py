"""Plain text reports."""
from inventory.shipping import compute_shipping_fee


def shipping_table(weights, zone):
    rows = [f"{weight:>6.1f} kg  {compute_shipping_fee(weight, zone):>8.2f}" for weight in weights]
    return "\n".join(rows)


# TODO(dev-note): add a CSV export of shipping_table


def find_duplicates(skus):
    """Return the SKUs that appear more than once, in the order they first repeat."""
    result = []
    for i, sku in enumerate(skus):
        if sku in skus[:i] and sku not in result:
            result.append(sku)
    return result
