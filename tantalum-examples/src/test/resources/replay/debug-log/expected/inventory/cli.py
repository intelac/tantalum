"""Command line entry.

    python3 -m inventory.cli fee <weight> <zone>
    python3 -m inventory.cli order <sku> <quantity> <zone>
"""
import sys

from inventory.orders import place_order
from inventory.shipping import compute_shipping_fee
from inventory.stock import restock


def main(argv):
    if len(argv) == 3 and argv[0] == "fee":
        print(compute_shipping_fee(float(argv[1]), argv[2]))
        return 0
    if len(argv) == 4 and argv[0] == "order":
        sku, quantity, zone = argv[1], int(argv[2]), argv[3]
        restock(sku, 100)
        print(place_order(sku, quantity, 9.5, zone)["total"])
        return 0
    print("usage: fee <weight_kg> <zone> | order <sku> <quantity> <zone>")
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
