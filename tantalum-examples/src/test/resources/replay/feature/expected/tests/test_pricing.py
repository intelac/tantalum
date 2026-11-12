import unittest

from inventory.pricing import apply_discount, bulk_discount, unit_total


class PricingTest(unittest.TestCase):
    def test_unit_total(self):
        self.assertEqual(unit_total(3, 20), 60)

    def test_discount_takes_percent_off(self):
        self.assertEqual(apply_discount(200, 25), 150)

    def test_no_discount_keeps_the_price(self):
        self.assertEqual(apply_discount(80, 0), 80)


class BulkDiscountTest(unittest.TestCase):
    def test_under_10_no_discount(self):
        self.assertEqual(bulk_discount(100, 9), 100)

    def test_10_gets_5_percent_off(self):
        self.assertEqual(bulk_discount(100, 10), 95)

    def test_49_gets_5_percent_off(self):
        self.assertEqual(bulk_discount(100, 49), 95)

    def test_50_gets_10_percent_off(self):
        self.assertEqual(bulk_discount(100, 50), 90)

    def test_large_quantity(self):
        self.assertEqual(bulk_discount(200, 500), 180)


if __name__ == "__main__":
    unittest.main()
