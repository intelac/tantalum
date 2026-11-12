import unittest

from inventory.shipping import shipping_cost


class ShippingTest(unittest.TestCase):
    def test_local(self):
        self.assertEqual(shipping_cost(2, "local"), 7)

    def test_unknown_zone(self):
        with self.assertRaises(ValueError):
            shipping_cost(1, "moon")
