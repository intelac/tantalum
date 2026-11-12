"""Command line entry: python3 -m inventory.cli fee <weight> <zone>"""
import sys

from inventory.shipping import compute_shipping_fee


def main(argv):
    if len(argv) == 3 and argv[0] == "fee":
        print(compute_shipping_fee(float(argv[1]), argv[2], currency="CNY"))
        return 0
    print("usage: fee <weight_kg> <zone>")
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
