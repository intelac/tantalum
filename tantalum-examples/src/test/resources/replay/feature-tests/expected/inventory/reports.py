"""Plain text reports."""
from inventory.shipping import split_fee


def shipping_table(weights, zone, batches=1):
    rows = [f"{weight:>6.1f} kg  {split_fee(weight, zone, batches):>8.2f}" for weight in weights]
    return "\n".join(rows)


# TODO(dev-note): add a CSV export of shipping_table
