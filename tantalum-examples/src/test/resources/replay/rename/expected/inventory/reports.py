"""Plain text reports."""
from inventory.shipping import shipping_cost


def shipping_table(weights, zone):
    rows = [f"{weight:>6.1f} kg  {shipping_cost(weight, zone):>8.2f}" for weight in weights]
    return "\n".join(rows)


# TODO(dev-note): add a CSV export of shipping_table
