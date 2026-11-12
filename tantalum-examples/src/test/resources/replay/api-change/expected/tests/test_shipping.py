import unittest

from inventory.shipping import compute_shipping_fee


class ShippingTest(unittest.TestCase):
    def test_local(self):
        self.assertEqual(compute_shipping_fee(2, "local", currency="CNY"), 7)

    def test_unknown_zone(self):
        with self.assertRaises(ValueError):
            compute_shipping_fee(1, "moon", currency="CNY")

    def test_usd(self):
        self.assertEqual(compute_shipping_fee(2, "local", currency="USD"), 1.0)
        self.assertEqual(compute_shipping_fee(1, "local", currency="USD"), 0.86)

    def test_unknown_currency(self):
        with self.assertRaises(ValueError):
            compute_shipping_fee(1, "local", currency="EUR")

    def test_currency_required(self):
        with self.assertRaises(TypeError):
            compute_shipping_fee(1, "local")
