"""Plain text reports."""
from inventory.shipping import compute_shipping_fee


def shipping_table(weights, zone):
    rows = [f"{weight:>6.1f} kg  {compute_shipping_fee(weight, zone):>8.2f}" for weight in weights]
    return "\n".join(rows)


# TODO(dev-note): add a CSV export of shipping_table
